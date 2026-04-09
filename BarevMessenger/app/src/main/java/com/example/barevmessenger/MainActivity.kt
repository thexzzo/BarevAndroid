package com.example.barevmessenger

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {


    private lateinit var logView:        TextView
    private lateinit var messageInput:   EditText
    private lateinit var peerIp:         EditText
    private lateinit var peerPort:       EditText
    private lateinit var localIdInput:   EditText
    private lateinit var peerIdInput:    EditText
    private lateinit var statusInput:    EditText
    private lateinit var statusSpinner:  Spinner
    private lateinit var avatarView:     ImageView
    private lateinit var scrollView:     ScrollView


    private var socket: Socket?        = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile private var isConnected      = false
    @Volatile private var waitingForPong   = false
    @Volatile private var lastActivityTime = 0L
    @Volatile private var isComposingSent  = false

    private val pingIntervalMs = 30_000L
    private val pongTimeoutMs  = 15_000L
    private val typingPauseMs  = 2_000L

    private val uiHandler     = Handler(Looper.getMainLooper())
    private val pausedRunnable = Runnable {
        if (isConnected && isComposingSent) {
            sendPaused()
            isComposingSent = false
        }
    }



    private val pendingOutgoingFiles = mutableMapOf<String, String>()





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView       = findViewById(R.id.logView)
        messageInput  = findViewById(R.id.messageInput)
        peerIp        = findViewById(R.id.peerIp)
        peerPort      = findViewById(R.id.peerPort)
        localIdInput  = findViewById(R.id.localIdInput)
        peerIdInput   = findViewById(R.id.peerIdInput)
        statusInput   = findViewById(R.id.statusInput)
        statusSpinner = findViewById(R.id.statusSpinner)
        avatarView    = findViewById(R.id.avatarView)
        scrollView    = findViewById(R.id.scrollView)


        peerPort.setText("5299")
        localIdInput.setText("android@barev")
        peerIdInput.setText("pc@barev")
        statusInput.setText("Hello from Android!")


        val statusOptions = arrayOf("Available", "Away", "Do Not Disturb")
        statusSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, statusOptions)


        findViewById<Button>(R.id.connectButton).setOnClickListener    { connect() }
        findViewById<Button>(R.id.listenButton).setOnClickListener     { listen() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { disconnect() }
        findViewById<Button>(R.id.sendButton).setOnClickListener       { sendMessage() }
        findViewById<Button>(R.id.presenceButton).setOnClickListener   { sendPresence() }
        findViewById<Button>(R.id.avatarButton).setOnClickListener     { sendAvatar() }
        findViewById<Button>(R.id.fileButton).setOnClickListener       { sendFilePlaceholder() }

        setupTypingNotifications()
    }





    private fun setupTypingNotifications() {
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isConnected) return
                val text = s?.toString() ?: ""
                uiHandler.removeCallbacks(pausedRunnable)
                if (text.isNotEmpty()) {
                    if (!isComposingSent) { sendComposing(); isComposingSent = true }
                    uiHandler.postDelayed(pausedRunnable, typingPauseMs)
                } else {
                    if (isComposingSent) { sendPaused(); isComposingSent = false }
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }





    private fun log(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")

            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }





    private fun connect() {
        thread {
            try {
                val ip   = peerIp.text.toString().trim()
                val port = peerPort.text.toString().trim().toIntOrNull() ?: 5299
                socket   = Socket(ip, port)
                setupStreams()
                onConnectionEstablished("Connected to peer")
            } catch (e: Exception) {
                log("Connect error: ${e.message}")
            }
        }
    }

    private fun listen() {
        thread {
            var serverSocket: ServerSocket? = null
            try {
                val port = peerPort.text.toString().trim().toIntOrNull() ?: 5299
                serverSocket = ServerSocket(port)
                log("Listening on port $port…")
                socket = serverSocket.accept()
                setupStreams()
                onConnectionEstablished("Peer connected")
            } catch (e: Exception) {
                log("Listen error: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun setupStreams() {
        val s = socket ?: throw IllegalStateException("Socket is null")
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
        reader = BufferedReader(InputStreamReader(s.getInputStream()))
    }

    private fun onConnectionEstablished(msg: String) {
        isConnected    = true
        waitingForPong = false
        isComposingSent = false
        touchActivity()
        log(msg)
        sendStreamStart()
        sendPresence()
        startReadLoop()
        startPingManager()
    }

    fun disconnect() {
        if (isConnected) sendOfflinePresence()
        cleanupConnectionState()
        log("Disconnected")
    }

    private fun cleanupConnectionState() {
        isConnected    = false
        waitingForPong = false
        isComposingSent = false
        uiHandler.removeCallbacks(pausedRunnable)
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; reader = null; socket = null
    }





    private fun startReadLoop() {
        thread {
            try {
                while (isConnected) {
                    val line = reader?.readLine() ?: break
                    touchActivity()
                    handleIncoming(line)
                }
            } catch (e: Exception) {
                if (isConnected) log("Read error: ${e.message}")
            } finally {
                if (isConnected) { log("Connection closed"); cleanupConnectionState() }
            }
        }
    }





    private fun handleIncoming(raw: String) {
        log("Recv: $raw")

        when (val stanza = BarevProtocol.parseStanza(raw)) {

            is ParsedStanza.StreamStart ->
                log("Stream started by ${displayId(stanza.from)}")

            is ParsedStanza.PresenceOnline -> {
                val statusPart = if (stanza.statusText.isNotEmpty()) " – ${stanza.statusText}" else ""
                log("${displayId(stanza.from)} is online$statusPart")
            }

            is ParsedStanza.PresenceAway -> {
                val statusPart = if (stanza.statusText.isNotEmpty()) " – ${stanza.statusText}" else ""
                log("${displayId(stanza.from)} is away$statusPart")
            }

            is ParsedStanza.PresenceDnd -> {
                val statusPart = if (stanza.statusText.isNotEmpty()) " – ${stanza.statusText}" else ""
                log("${displayId(stanza.from)} is busy (do not disturb)$statusPart")
            }

            is ParsedStanza.PresenceOffline ->
                log("${displayId(stanza.from)} went offline")

            is ParsedStanza.Message ->
                log("${displayId(stanza.from)}: ${stanza.body}")

            is ParsedStanza.Typing ->
                log("${displayId(stanza.from)} is typing…")

            is ParsedStanza.Paused ->
                log("${displayId(stanza.from)} stopped typing")

            is ParsedStanza.Ping -> {
                send(BarevProtocol.makePong(), countAsActivity = false, silent = true)
                log("Ping received → sent pong")
            }

            is ParsedStanza.Pong -> {
                waitingForPong = false
                touchActivity()
                log("Pong received")
            }


            is ParsedStanza.Avatar -> {
                log("Avatar received from ${displayId(stanza.from)}")
                if (stanza.base64Jpeg.isNotEmpty()) {
                    runOnUiThread {
                        try {
                            val bytes  = Base64.decode(stanza.base64Jpeg, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) avatarView.setImageBitmap(bitmap)
                        } catch (e: Exception) {
                            log("Could not decode avatar: ${e.message}")
                        }
                    }
                }
            }


            is ParsedStanza.VCardRequest -> {
                log("${displayId(stanza.from)} requested our avatar – sending")
                sendAvatar()
            }


            is ParsedStanza.FileOffer -> {
                log("${displayId(stanza.from)} wants to send file: " +
                        "${stanza.fileName} (${stanza.fileSize} bytes) [sid=${stanza.sid}]")
                log("Auto-accepting file offer…")
                val accept = BarevProtocol.makeFileAccept(getLocalId(), getPeerId(), stanza.sid)
                send(accept)

            }

            is ParsedStanza.FileAccept ->
                log("${displayId(stanza.from)} accepted file transfer [sid=${stanza.sid}]")


            is ParsedStanza.FileReject ->
                log("${displayId(stanza.from)} rejected file transfer [sid=${stanza.sid}]")

            is ParsedStanza.Raw ->
                log("Unknown stanza: ${stanza.content}")
        }
    }





    private fun send(data: String, countAsActivity: Boolean = true, silent: Boolean = false) {
        thread {
            try {
                writer?.write(data)
                writer?.newLine()
                writer?.flush()
                if (countAsActivity) touchActivity()
                if (!silent) log("Sent: $data")
            } catch (e: Exception) {
                log("Send error: ${e.message}")
            }
        }
    }

    private fun sendStreamStart() {
        send(BarevProtocol.makeStreamStart(getLocalId(), getPeerId()))
    }


    private fun sendPresence() {
        val status = when (statusSpinner.selectedItemPosition) {
            1    -> PresenceStatus.AWAY
            2    -> PresenceStatus.DND
            else -> PresenceStatus.AVAILABLE
        }
        val statusText = statusInput.text.toString().trim()
        val stanza = BarevProtocol.makePresence(
            from       = getLocalId(),
            to         = getPeerId(),
            status     = status,
            statusText = statusText
        )
        send(stanza)
    }

    private fun sendOfflinePresence() {
        send(BarevProtocol.makeUnavailablePresence(getLocalId(), getPeerId()))
    }

    private fun sendMessage() {
        val msg = messageInput.text.toString().trim()
        if (msg.isEmpty()) { log("Message is empty"); return }
        send(BarevProtocol.makeChatMessage(getLocalId(), getPeerId(), msg))
        runOnUiThread { messageInput.setText("") }
        if (isComposingSent) { sendPaused(); isComposingSent = false }
    }

    private fun sendComposing() {
        send(BarevProtocol.makeComposing(getLocalId(), getPeerId()), silent = true)
        log("Typing notification sent")
    }

    private fun sendPaused() {
        send(BarevProtocol.makePaused(getLocalId(), getPeerId()), silent = true)
        log("Paused notification sent")
    }






    private fun sendAvatar() {
        thread {
            try {

                val bitmap = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
                val stream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                val stanza = BarevProtocol.makeVCardUpdate(getLocalId(), getPeerId(), base64)
                send(stanza)
                log("Avatar sent (${stream.size()} bytes as Base64)")
            } catch (e: Exception) {
                log("Avatar send error: ${e.message}")
            }
        }
    }






    private fun sendFilePlaceholder() {
        val sid      = UUID.randomUUID().toString().take(8)
        val fileName = "example.txt"
        val fileSize = 1234L
        pendingOutgoingFiles[sid] = fileName
        val stanza = BarevProtocol.makeFileOffer(
            from     = getLocalId(),
            to       = getPeerId(),
            sid      = sid,
            fileName = fileName,
            fileSize = fileSize
        )
        send(stanza)
        log("File offer sent: $fileName [sid=$sid]")
        log("(Replace sendFilePlaceholder() with a real file picker)")
    }





    private fun startPingManager() {
        thread {
            while (isConnected) {
                try {
                    Thread.sleep(5_000)
                    if (!isConnected) break
                    val idle = System.currentTimeMillis() - lastActivityTime
                    if (waitingForPong) {
                        if (idle >= pongTimeoutMs) {
                            log("Pong timeout – peer appears disconnected")
                            disconnect()
                            break
                        }
                        continue
                    }
                    if (idle >= pingIntervalMs) {
                        waitingForPong = true
                        send(BarevProtocol.makePing(), countAsActivity = false, silent = true)
                        log("Ping sent")
                    }
                } catch (e: Exception) {
                    if (isConnected) log("Ping manager stopped: ${e.message}")
                    break
                }
            }
        }
    }





    private fun touchActivity() { lastActivityTime = System.currentTimeMillis() }

    private fun getLocalId(): String {
        val v = localIdInput.text.toString().trim()
        return if (v.isEmpty()) "android@barev" else v
    }

    private fun getPeerId(): String {
        val v = peerIdInput.text.toString().trim()
        return if (v.isEmpty()) "pc@barev" else v
    }

    private fun displayId(value: String) = if (value.isBlank()) "Peer" else value
}
