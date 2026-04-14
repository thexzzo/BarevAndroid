package com.example.barevmessenger

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ChatActivity : AppCompatActivity() {

    private lateinit var chatView:      TextView
    private lateinit var messageInput:  EditText
    private lateinit var statusInput:   EditText
    private lateinit var statusSpinner: Spinner
    private lateinit var scrollView:    ScrollView
    private lateinit var connectButton: Button
    private lateinit var titleBar:      TextView

    private var localId  = ""
    private var peerId   = ""
    private var peerIp   = ""
    private var peerPort = 1337

    private var socket: Socket?         = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var serverSocket: ServerSocket? = null

    @Volatile private var isConnected       = false
    @Volatile private var isInitiator       = false
    @Volatile private var streamEstablished = false
    @Volatile private var lastActivityTime  = 0L
    @Volatile private var pingFailCount     = 0
    @Volatile private var waitingForPong    = false
    @Volatile private var isComposingSent   = false

    private val typingPauseMs = 2_000L
    private val uiHandler     = Handler(Looper.getMainLooper())
    private val pausedRunnable = Runnable {
        if (isConnected && isComposingSent) isComposingSent = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        chatView      = findViewById(R.id.chatView)
        messageInput  = findViewById(R.id.messageInput)
        statusInput   = findViewById(R.id.statusInput)
        statusSpinner = findViewById(R.id.statusSpinner)
        scrollView    = findViewById(R.id.scrollView)
        connectButton = findViewById(R.id.connectButton)
        titleBar      = findViewById(R.id.titleBar)

        localId  = intent.getStringExtra("localId")  ?: "android@barev"
        peerIp   = intent.getStringExtra("peerIpv6") ?: ""
        peerPort = intent.getStringExtra("peerPort")?.toIntOrNull() ?: 1337
        val peerNick = intent.getStringExtra("peerNick") ?: "peer"
        peerId   = "$peerNick@$peerIp"

        titleBar.text = peerNick

        val statusOptions = arrayOf("Available", "Away", "Extended Away", "Do Not Disturb")
        statusSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, statusOptions)

        connectButton.setOnClickListener { connectOrListen() }
        findViewById<Button>(R.id.sendButton).setOnClickListener     { sendMessage() }
        findViewById<Button>(R.id.presenceButton).setOnClickListener { sendPresence() }

        setupTypingDetection()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun setupTypingDetection() {
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!isConnected || !streamEstablished) return
                uiHandler.removeCallbacks(pausedRunnable)
                if (!s.isNullOrEmpty()) {
                    isComposingSent = true
                    uiHandler.postDelayed(pausedRunnable, typingPauseMs)
                } else {
                    isComposingSent = false
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun showMessage(msg: String) {
        runOnUiThread {
            chatView.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun debugLog(msg: String) { Log.d("Barev", msg) }

    private fun connectOrListen() {
        if (isConnected) return

        runOnUiThread { connectButton.isEnabled = false }

        if (peerIp.isNotEmpty()) {
            thread {
                try {
                    debugLog("Outgoing: trying $peerIp:$peerPort")
                    val s = Socket(peerIp, peerPort)
                    synchronized(this) {
                        if (!isConnected) {
                            socket = s
                            setupStreams()
                            isInitiator = true
                            stopListening()
                            onSocketReady()
                            sendStreamStart()
                        } else {
                            try { s.close() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    debugLog("Outgoing failed: ${e.message}")
                }
            }
        }

        thread {
            try {
                serverSocket = ServerSocket(peerPort)
                showMessage("Ready on port $peerPort…")
                val s = serverSocket?.accept() ?: return@thread
                synchronized(this) {
                    if (!isConnected) {
                        socket = s
                        setupStreams()
                        isInitiator = false
                        stopListening()
                        onSocketReady()
                    } else {
                        try { s.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (!isConnected) debugLog("Listen ended: ${e.message}")
            }
        }
    }

    private fun stopListening() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun setupStreams() {
        val s = socket ?: throw IllegalStateException("Socket null")
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), "UTF-8"))
        reader = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
    }

    private fun onSocketReady() {
        isConnected       = true
        streamEstablished = false
        pingFailCount     = 0
        waitingForPong    = false
        touchActivity()
        showMessage("--- Connection established ---")
        startReadLoop()
        startPingManager()
        runOnUiThread {
            connectButton.text      = "Disconnect"
            connectButton.isEnabled = true
            connectButton.setOnClickListener { disconnect() }
        }
    }

    private fun disconnect() {
        if (isConnected && streamEstablished) {
            sendPresenceRaw(BarevProtocol.makePresence(PresenceStatus.OFFLINE))
            sendRaw(BarevProtocol.makeStreamEnd(), silent = true)
        }
        cleanup()
        showMessage("--- Disconnected ---")
    }

    private fun cleanup() {
        isConnected       = false
        streamEstablished = false
        waitingForPong    = false
        isComposingSent   = false
        uiHandler.removeCallbacks(pausedRunnable)
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null; reader = null; socket = null
        runOnUiThread {
            connectButton.text      = "Connect"
            connectButton.isEnabled = true
            connectButton.setOnClickListener { connectOrListen() }
        }
    }

    private fun startReadLoop() {
        thread {
            val sb = StringBuilder()
            try {
                val buf = CharArray(1024)
                while (isConnected) {
                    val n = reader?.read(buf) ?: break
                    if (n == -1) break
                    val chunk = String(buf, 0, n)
                    touchActivity()
                    pingFailCount  = 0
                    waitingForPong = false
                    debugLog("RAW IN: $chunk")
                    sb.append(chunk)
                    processBuffer(sb)
                }
            } catch (e: Exception) {
                if (isConnected) debugLog("Read error: ${e.message}")
            } finally {
                if (isConnected) {
                    showMessage("--- Connection closed ---")
                    cleanup()
                }
            }
        }
    }

    private fun processBuffer(sb: StringBuilder) {
        val text = sb.toString()
        if (text.isBlank()) return

        for (tag in listOf("</message>", "</presence>", "</iq>", "</stream:stream>")) {
            val idx = text.indexOf(tag)
            if (idx != -1) {
                val end        = idx + tag.length
                val stanzaText = text.substring(0, end).trim()
                if (stanzaText.isNotEmpty()) {
                    debugLog("STANZA: $stanzaText")
                    handleStanza(BarevProtocol.parseStanza(stanzaText))
                }
                sb.delete(0, end)
                if (sb.isNotBlank()) processBuffer(sb)
                return
            }
        }

        for (tag in listOf("<presence/>", "<presence type=\"unavailable\"/>")) {
            val idx = text.indexOf(tag)
            if (idx != -1) {
                val end = idx + tag.length
                debugLog("STANZA: $tag")
                handleStanza(BarevProtocol.parseStanza(tag))
                sb.delete(0, end)
                if (sb.isNotBlank()) processBuffer(sb)
                return
            }
        }

        if (text.contains("stream:stream")) {
            val gtIdx = text.indexOf(">")
            if (gtIdx != -1) {
                val stanzaText = text.substring(0, gtIdx + 1).trim()
                debugLog("STANZA: $stanzaText")
                handleStanza(BarevProtocol.parseStanza(stanzaText))
                sb.delete(0, gtIdx + 1)
                if (sb.isNotBlank()) processBuffer(sb)
            }
        }
    }

    private fun handleStanza(stanza: ParsedStanza) {
        when (stanza) {

            is ParsedStanza.StreamStart -> {
                debugLog("Stream started from ${stanza.from}")
                if (!isInitiator) sendStreamStart()
                streamEstablished = true
                sendPresence()
                showMessage("--- Connected to ${stanza.from} ---")
            }

            is ParsedStanza.StreamEnd -> {
                showMessage("--- Peer closed the stream ---")
                cleanup()
            }

            is ParsedStanza.PresenceUpdate -> {
                val label = when (stanza.status) {
                    PresenceStatus.AVAILABLE -> "online"
                    PresenceStatus.AWAY      -> "away"
                    PresenceStatus.XA        -> "extended away"
                    PresenceStatus.DND       -> "do not disturb"
                    else                     -> "online"
                }
                val suffix = if (stanza.statusText.isNotEmpty()) " – ${stanza.statusText}" else ""
                showMessage("*** Peer is $label$suffix")
            }

            is ParsedStanza.PresenceOffline ->
                showMessage("*** Peer went offline")

            is ParsedStanza.Message -> {
                val sender = if (stanza.from.isNotEmpty()) stanza.from.substringBefore("@") else "Peer"
                showMessage("$sender: ${stanza.body}")
            }

            is ParsedStanza.Ping -> {
                sendRaw(BarevProtocol.makePong(localId, peerId, stanza.id), silent = true)
                debugLog("Ping received, pong sent")
            }

            is ParsedStanza.Pong -> {
                waitingForPong = false
                pingFailCount  = 0
                touchActivity()
            }

            is ParsedStanza.FileOffer ->
                showMessage("*** ${stanza.from.substringBefore("@")} wants to send: ${stanza.fileName} (${stanza.fileSize} bytes)")

            is ParsedStanza.FileAccept ->
                showMessage("*** File transfer accepted")

            is ParsedStanza.FileReject ->
                showMessage("*** File transfer rejected")

            is ParsedStanza.StreamhostProposal ->
                debugLog("Streamhost proposed: ${stanza.host}:${stanza.port}")

            is ParsedStanza.StreamhostSelected ->
                debugLog("Streamhost selected: ${stanza.usedJid}")

            is ParsedStanza.Raw ->
                debugLog("Unhandled: ${stanza.content}")
        }
    }

    private fun sendStreamStart() {
        sendRaw(BarevProtocol.makeStreamStart(localId, peerId))
    }

    private fun sendPresence() {
        val status = when (statusSpinner.selectedItemPosition) {
            1    -> PresenceStatus.AWAY
            2    -> PresenceStatus.XA
            3    -> PresenceStatus.DND
            else -> PresenceStatus.AVAILABLE
        }
        sendPresenceRaw(BarevProtocol.makePresence(status, statusInput.text.toString().trim()))
    }

    private fun sendPresenceRaw(stanza: String) { sendRaw(stanza) }

    private fun sendMessage() {
        if (!streamEstablished) { showMessage("Not connected yet"); return }
        val msg = messageInput.text.toString().trim()
        if (msg.isEmpty()) return
        sendRaw(BarevProtocol.makeChatMessage(peerId, msg))
        showMessage("${localId.substringBefore("@")}: $msg")
        runOnUiThread { messageInput.setText("") }
        isComposingSent = false
    }

    private fun sendRaw(data: String, silent: Boolean = false) {
        thread {
            try {
                writer?.write(data)
                writer?.newLine()
                writer?.flush()
                touchActivity()
                debugLog("RAW OUT: $data")
            } catch (e: Exception) {
                if (!silent) showMessage("Send error: ${e.message}")
            }
        }
    }

    private fun startPingManager() {
        thread {
            while (isConnected) {
                try {
                    Thread.sleep(10_000)
                    if (!isConnected) break
                    val idle = System.currentTimeMillis() - lastActivityTime
                    if (idle >= 120_000) {
                        showMessage("*** Connection lost")
                        cleanup()
                        break
                    }
                } catch (e: Exception) { break }
            }
        }
    }

    private fun touchActivity() { lastActivityTime = System.currentTimeMillis() }
}
