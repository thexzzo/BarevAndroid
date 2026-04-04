package com.example.barevmessenger

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var messageInput: EditText
    private lateinit var peerIp: EditText
    private lateinit var peerPort: EditText
    private lateinit var localIdInput: EditText
    private lateinit var peerIdInput: EditText
    private lateinit var statusInput: EditText

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null

    @Volatile
    private var isConnected = false

    @Volatile
    private var waitingForPong = false

    @Volatile
    private var lastActivityTime = 0L

    @Volatile
    private var isComposingSent = false

    private val pingIntervalMs = 30000L
    private val pongTimeoutMs = 15000L
    private val typingPauseMs = 2000L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val pausedRunnable = Runnable {
        if (isConnected && isComposingSent) {
            sendPaused()
            isComposingSent = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        messageInput = findViewById(R.id.messageInput)
        peerIp = findViewById(R.id.peerIp)
        peerPort = findViewById(R.id.peerPort)
        localIdInput = findViewById(R.id.localIdInput)
        peerIdInput = findViewById(R.id.peerIdInput)
        statusInput = findViewById(R.id.statusInput)

        peerPort.setText("5299")
        localIdInput.setText("android@barev")
        peerIdInput.setText("pc@barev")
        statusInput.setText("online")

        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.listenButton).setOnClickListener { listen() }
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendMessage() }
        findViewById<Button>(R.id.presenceButton).setOnClickListener { sendPresence() }
        findViewById<Button>(R.id.disconnectButton).setOnClickListener { disconnect() }

        setupTypingNotifications()
    }

    private fun setupTypingNotifications() {
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isConnected) return

                val text = s?.toString() ?: ""
                uiHandler.removeCallbacks(pausedRunnable)

                if (text.isNotEmpty()) {
                    if (!isComposingSent) {
                        sendComposing()
                        isComposingSent = true
                    }
                    uiHandler.postDelayed(pausedRunnable, typingPauseMs)
                } else {
                    if (isComposingSent) {
                        sendPaused()
                        isComposingSent = false
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun log(msg: String) {
        runOnUiThread {
            logView.append("$msg\n")
        }
    }

    private fun connect() {
        thread {
            try {
                val ip = peerIp.text.toString().trim()
                val port = peerPort.text.toString().trim().toIntOrNull() ?: 5299

                socket = Socket(ip, port)
                setupStreams()

                isConnected = true
                waitingForPong = false
                isComposingSent = false
                touchActivity()

                log("Connected to peer")
                sendStreamStart()
                sendPresence()

                startListening()
                startPingManager()

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
                log("Listening on port $port...")

                socket = serverSocket.accept()
                setupStreams()

                isConnected = true
                waitingForPong = false
                isComposingSent = false
                touchActivity()

                log("Peer connected")
                sendStreamStart()
                sendPresence()

                startListening()
                startPingManager()

            } catch (e: Exception) {
                log("Listen error: ${e.message}")
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setupStreams() {
        val currentSocket = socket ?: throw IllegalStateException("Socket is null")
        writer = BufferedWriter(OutputStreamWriter(currentSocket.getOutputStream()))
        reader = BufferedReader(InputStreamReader(currentSocket.getInputStream()))
    }

    private fun send(data: String, countAsActivity: Boolean = true, logOutgoing: Boolean = true) {
        thread {
            try {
                writer?.write(data)
                writer?.newLine()
                writer?.flush()

                if (countAsActivity) {
                    touchActivity()
                }

                if (logOutgoing) {
                    log("Sent: $data")
                }
            } catch (e: Exception) {
                log("Send error: ${e.message}")
            }
        }
    }

    private fun sendStreamStart() {
        val from = getLocalId()
        val to = getPeerId()
        send("<stream from=\"$from\" to=\"$to\" version=\"1.0\"/>")
    }

    private fun sendPresence() {
        val from = getLocalId()
        val to = getPeerId()
        val status = escape(statusInput.text.toString().trim())
        send("<presence from=\"$from\" to=\"$to\"><status>$status</status></presence>")
    }

    private fun sendOfflinePresence() {
        val from = getLocalId()
        val to = getPeerId()
        send("<presence from=\"$from\" to=\"$to\" type=\"unavailable\"/>")
    }

    private fun sendMessage() {
        val msg = messageInput.text.toString().trim()
        if (msg.isEmpty()) {
            log("Message is empty")
            return
        }

        val from = getLocalId()
        val to = getPeerId()

        send("<message from=\"$from\" to=\"$to\" type=\"chat\"><body>${escape(msg)}</body></message>")
        messageInput.setText("")

        if (isComposingSent) {
            sendPaused()
            isComposingSent = false
        }
    }

    private fun sendComposing() {
        val from = getLocalId()
        val to = getPeerId()
        send(
            "<message from=\"$from\" to=\"$to\" type=\"chat\">" +
                    "<composing xmlns=\"http://jabber.org/protocol/chatstates\"/>" +
                    "</message>",
            logOutgoing = false
        )
        log("Typing notification sent")
    }

    private fun sendPaused() {
        val from = getLocalId()
        val to = getPeerId()
        send(
            "<message from=\"$from\" to=\"$to\" type=\"chat\">" +
                    "<paused xmlns=\"http://jabber.org/protocol/chatstates\"/>" +
                    "</message>",
            logOutgoing = false
        )
        log("Paused notification sent")
    }

    private fun startListening() {
        thread {
            try {
                while (isConnected) {
                    val line = reader?.readLine() ?: break
                    touchActivity()
                    handleIncoming(line)
                }
            } catch (e: Exception) {
                if (isConnected) {
                    log("Read error: ${e.message}")
                }
            } finally {
                if (isConnected) {
                    log("Connection closed")
                }
                cleanupConnectionState()
            }
        }
    }

    private fun handleIncoming(data: String) {
        log("Recv: $data")

        when {
            data.contains("<stream") -> {
                val from = extractAttribute(data, "from")
                if (from.isNotEmpty()) {
                    log("Stream started by $from")
                } else {
                    log("Stream started")
                }
            }

            data.contains("<presence") -> {
                val type = extractAttribute(data, "type")
                val from = extractAttribute(data, "from")
                val status = unescape(extractTag(data, "status"))

                when {
                    type == "unavailable" -> log("${displayId(from)} went offline")
                    status.isNotEmpty() -> log("${displayId(from)} status: $status")
                    else -> log("Presence received from ${displayId(from)}")
                }
            }

            data.contains("<message") && data.contains("<body>") -> {
                val from = extractAttribute(data, "from")
                val msg = unescape(extractTag(data, "body"))
                log("${displayId(from)} says: $msg")
            }

            data.contains("<composing") -> {
                val from = extractAttribute(data, "from")
                log("${displayId(from)} is typing...")
            }

            data.contains("<paused") -> {
                val from = extractAttribute(data, "from")
                log("${displayId(from)} stopped typing")
            }

            data.contains("<ping") -> {
                send("<pong/>", countAsActivity = false, logOutgoing = false)
                log("Replied with <pong/>")
            }

            data.contains("<pong") -> {
                waitingForPong = false
                touchActivity()
                log("Pong received")
            }

            else -> {
                log("Unknown stanza")
            }
        }
    }

    private fun startPingManager() {
        thread {
            while (isConnected) {
                try {
                    Thread.sleep(5000)

                    if (!isConnected) break

                    val idleTime = System.currentTimeMillis() - lastActivityTime

                    if (waitingForPong) {
                        if (idleTime >= pongTimeoutMs) {
                            log("Pong timeout. Peer appears disconnected.")
                            disconnect()
                            break
                        }
                        continue
                    }

                    if (idleTime >= pingIntervalMs) {
                        waitingForPong = true
                        send("<ping/>", countAsActivity = false, logOutgoing = false)
                        log("Ping sent")
                    }

                } catch (e: Exception) {
                    if (isConnected) {
                        log("Ping manager stopped: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private fun disconnect() {
        if (isConnected) {
            sendOfflinePresence()
        }
        cleanupConnectionState()
        log("Disconnected")
    }

    private fun cleanupConnectionState() {
        isConnected = false
        waitingForPong = false
        isComposingSent = false
        uiHandler.removeCallbacks(pausedRunnable)

        try {
            writer?.close()
        } catch (_: Exception) {
        }

        try {
            reader?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        writer = null
        reader = null
        socket = null
    }

    private fun touchActivity() {
        lastActivityTime = System.currentTimeMillis()
    }

    private fun getLocalId(): String {
        val value = localIdInput.text.toString().trim()
        return if (value.isEmpty()) "android@barev" else value
    }

    private fun getPeerId(): String {
        val value = peerIdInput.text.toString().trim()
        return if (value.isEmpty()) "pc@barev" else value
    }

    private fun displayId(value: String): String {
        return if (value.isBlank()) "Peer" else value
    }

    private fun extractTag(data: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        return if (data.contains(open) && data.contains(close)) {
            data.substringAfter(open).substringBefore(close)
        } else {
            ""
        }
    }

    private fun extractAttribute(data: String, attribute: String): String {
        val doubleQuotePattern = "$attribute=\""
        val singleQuotePattern = "$attribute='"

        return when {
            data.contains(doubleQuotePattern) ->
                data.substringAfter(doubleQuotePattern).substringBefore("\"")

            data.contains(singleQuotePattern) ->
                data.substringAfter(singleQuotePattern).substringBefore("'")

            else -> ""
        }
    }

    private fun escape(s: String): String {
        return s.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun unescape(s: String): String {
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}