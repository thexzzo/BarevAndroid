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
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null

    private lateinit var peerIp: EditText
    private lateinit var peerPort: EditText
    private lateinit var messageInput: EditText
    private lateinit var listenButton: Button
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button
    private lateinit var chatLog: TextView

    private var isTypingSent = false
    private val typingHandler = Handler(Looper.getMainLooper())
    private val pausedRunnable = Runnable {
        sendPaused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        peerIp = findViewById(R.id.peerIp)
        peerPort = findViewById(R.id.peerPort)
        messageInput = findViewById(R.id.messageInput)
        listenButton = findViewById(R.id.listenButton)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        chatLog = findViewById(R.id.chatLog)

        peerPort.setText("5299")

        listenButton.setOnClickListener {
            startListening()
        }

        connectButton.setOnClickListener {
            connectToPeer()
        }

        sendButton.setOnClickListener {
            sendMessage()
        }

        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (writer == null || socket == null) return

                if (!isTypingSent) {
                    sendTyping()
                    isTypingSent = true
                }

                typingHandler.removeCallbacks(pausedRunnable)
                typingHandler.postDelayed(pausedRunnable, 1500)
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun startListening() {
        val portText = peerPort.text.toString().trim()

        if (portText.isEmpty()) {
            appendLog("Please enter a port")
            return
        }

        val port = portText.toIntOrNull()
        if (port == null) {
            appendLog("Invalid port")
            return
        }

        Thread {
            try {
                appendLog("Starting listener on port $port ...")

                serverSocket?.close()
                serverSocket = ServerSocket(port)

                appendLog("Waiting for incoming connection...")

                socket = serverSocket!!.accept()

                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                appendLog("Peer connected: ${socket!!.inetAddress.hostAddress}")

                sendInitialProtocolStanzas()
                startReadingMessages()

            } catch (e: Exception) {
                appendLog("Listener failed: ${e.message}")
            }
        }.start()
    }

    private fun connectToPeer() {
        val ip = peerIp.text.toString().trim()
        val portText = peerPort.text.toString().trim()

        if (ip.isEmpty() || portText.isEmpty()) {
            appendLog("Please enter peer IP and port")
            return
        }

        val port = portText.toIntOrNull()
        if (port == null) {
            appendLog("Invalid port")
            return
        }

        Thread {
            try {
                appendLog("Resolving address...")

                val address: InetAddress = InetAddress.getByName(ip)
                appendLog("Resolved address: ${address.hostAddress}")

                appendLog("Connecting to [$ip]:$port ...")

                socket = Socket(address, port)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                reader = BufferedReader(InputStreamReader(socket!!.getInputStream()))

                appendLog("Connected successfully")

                sendInitialProtocolStanzas()
                startReadingMessages()

            } catch (e: Exception) {
                appendLog("Connection failed: ${e.message}")
            }
        }.start()
    }

    private fun sendInitialProtocolStanzas() {
        val fromJid = getLocalJid()
        val toJid = getPeerJid()

        val stream = BarevProtocol.makeStreamStart(fromJid, toJid)
        writer?.println(stream)
        appendLog("Sent: <stream:stream>")

        val presence = BarevProtocol.makePresence(fromJid)
        writer?.println(presence)
        appendLog("Sent: <presence/>")
    }

    private fun startReadingMessages() {
        Thread {
            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    handleIncomingStanza(line)
                }
            } catch (e: Exception) {
                appendLog("Disconnected: ${e.message}")
            }
        }.start()
    }

    private fun handleIncomingStanza(stanza: String) {
        when (val parsed = BarevProtocol.parseStanza(stanza)) {
            is ParsedStanza.StreamStart -> appendLog("Peer started XMPP stream")
            is ParsedStanza.PresenceOnline -> appendLog("Peer is online")
            is ParsedStanza.PresenceOffline -> appendLog("Peer is offline")
            is ParsedStanza.Message -> appendLog("Peer: ${parsed.body}")
            is ParsedStanza.Typing -> appendLog("Peer is typing...")
            is ParsedStanza.Paused -> appendLog("Peer stopped typing")
            is ParsedStanza.Raw -> appendLog("Raw: ${parsed.content}")
        }
    }

    private fun sendMessage() {
        val msg = messageInput.text.toString().trim()

        if (msg.isBlank()) {
            appendLog("Message is empty")
            return
        }

        Thread {
            try {
                if (writer == null || socket == null) {
                    appendLog("No active connection")
                    return@Thread
                }

                val xmlMessage = BarevProtocol.makeChatMessage(
                    from = getLocalJid(),
                    to = getPeerJid(),
                    body = msg
                )

                writer?.println(xmlMessage)
                appendLog("Me: $msg")

                runOnUiThread {
                    messageInput.text.clear()
                }

                sendPaused()
                isTypingSent = false
                typingHandler.removeCallbacks(pausedRunnable)

            } catch (e: Exception) {
                appendLog("Send failed: ${e.message}")
            }
        }.start()
    }

    private fun sendTyping() {
        Thread {
            try {
                if (writer == null || socket == null) return@Thread

                val typing = BarevProtocol.makeComposing(
                    from = getLocalJid(),
                    to = getPeerJid()
                )

                writer?.println(typing)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun sendPaused() {
        Thread {
            try {
                if (writer == null || socket == null) return@Thread

                val paused = BarevProtocol.makePaused(
                    from = getLocalJid(),
                    to = getPeerJid()
                )

                writer?.println(paused)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun getLocalJid(): String {
        val localAddress = socket?.localAddress?.hostAddress ?: "local"
        return "phone@$localAddress"
    }

    private fun getPeerJid(): String {
        val peerAddress = peerIp.text.toString().trim().ifEmpty { "peer" }
        return "peer@$peerAddress"
    }

    private fun appendLog(text: String) {
        runOnUiThread {
            chatLog.append("\n$text")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            typingHandler.removeCallbacks(pausedRunnable)

            if (writer != null) {
                val unavailable = BarevProtocol.makeUnavailablePresence(getLocalJid())
                writer?.println(unavailable)
            }
            reader?.close()
            writer?.close()
            socket?.close()
            serverSocket?.close()
        } catch (_: Exception) {
        }
    }
}