package com.example.barevmessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class ChatMessage(
    val timestamp: String,
    val sender: String,
    val body: String,
    val isSystem: Boolean = false
)

data class BuddyConnection(
    val nick: String,
    val ipv6: String,
    val port: Int,
    var socket: Socket? = null,
    var writer: BufferedWriter? = null,
    var reader: BufferedReader? = null,
    var isConnected: Boolean = false,
    var streamEstablished: Boolean = false,
    var isInitiator: Boolean = false,
    var status: PresenceStatus = PresenceStatus.OFFLINE,
    var isTyping: Boolean = false,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var lastActivityTime: Long = 0L
) {
    val peerId get() = "$nick@$ipv6"
}

class BarevService : Service() {

    inner class BarevBinder : Binder() {
        fun getService(): BarevService = this@BarevService
    }

    private val binder     = BarevBinder()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    val connections = ConcurrentHashMap<String, BuddyConnection>()
    var localId     = ""
    var listener: ServiceListener? = null
    private var globalServerSocket: ServerSocket? = null

    interface ServiceListener {
        fun onMessageReceived(nick: String)
        fun onStatusChanged(nick: String)
        fun onConnectionStateChanged(nick: String)
        fun onTypingChanged(nick: String)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
    }

    private fun startForegroundNotification() {
        val channelId = "barev_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Barev Messenger",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Barev Messenger")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        connections.values.forEach { cleanupConn(it) }
    }

    fun timestamp(): String = timeFormat.format(Date())

    fun startListening(port: Int) {
        thread {
            try {
                globalServerSocket = ServerSocket(port)
                debugLog("Listener started on port $port")
                while (true) {
                    val s = globalServerSocket?.accept() ?: break
                    debugLog("Incoming from ${s.inetAddress.hostAddress}")
                    handleIncomingSocket(s)
                }
            } catch (e: Exception) {
                debugLog("Listener stopped: ${e.message}")
            }
        }
    }

    fun stopListening() {
        try { globalServerSocket?.close() } catch (_: Exception) {}
        globalServerSocket = null
    }

    private fun handleIncomingSocket(s: Socket) {
        thread {
            try {
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), "UTF-8"))
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
                val sb     = StringBuilder()
                val buf    = CharArray(1024)

                while (true) {
                    val n = reader.read(buf)
                    if (n == -1) break
                    sb.append(String(buf, 0, n))

                    if (sb.contains("stream:stream")) {
                        val fromAttr   = BarevProtocol.extractAttr(sb.toString(), "from")
                        val incomingIp = s.inetAddress.hostAddress ?: ""

                        val conn = connections.values.firstOrNull { c ->
                            fromAttr.contains(c.nick, ignoreCase = true) ||
                                    incomingIp.contains(c.ipv6, ignoreCase = true) ||
                                    c.ipv6.contains(incomingIp, ignoreCase = true)
                        }

                        if (conn == null) {
                            debugLog("Unknown incoming from $fromAttr, closing")
                            try { s.close() } catch (_: Exception) {}
                            return@thread
                        }

                        conn.socket            = s
                        conn.writer            = writer
                        conn.reader            = reader
                        conn.isConnected       = true
                        conn.isInitiator       = false
                        conn.streamEstablished = false
                        conn.lastActivityTime  = System.currentTimeMillis()
                        listener?.onConnectionStateChanged(conn.nick)
                        processBuffer(conn, sb)
                        startReadLoop(conn, sb)
                        return@thread
                    }

                    if (sb.length > 5000) break
                }
            } catch (e: Exception) {
                debugLog("handleIncomingSocket error: ${e.message}")
                try { s.close() } catch (_: Exception) {}
            }
        }
    }

    fun connectToBuddy(nick: String) {
        val conn = connections[nick] ?: return
        if (conn.isConnected) return
        thread {
            try {
                debugLog("Connecting to ${conn.peerId} at ${conn.ipv6}:${conn.port}")
                val s = Socket(conn.ipv6, conn.port)
                conn.socket            = s
                conn.writer            = BufferedWriter(OutputStreamWriter(s.getOutputStream(), "UTF-8"))
                conn.reader            = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))
                conn.isConnected       = true
                conn.isInitiator       = true
                conn.streamEstablished = false
                conn.lastActivityTime  = System.currentTimeMillis()
                listener?.onConnectionStateChanged(nick)
                sendStreamStart(conn)
                startReadLoop(conn, StringBuilder())
            } catch (e: Exception) {
                debugLog("Connect to $nick failed: ${e.message}")
                addSystemMessage(nick, "Could not connect: ${e.message}")
                listener?.onConnectionStateChanged(nick)
            }
        }
    }

    fun disconnectBuddy(nick: String) {
        val conn = connections[nick] ?: return
        if (conn.isConnected && conn.streamEstablished) {
            sendRaw(conn, BarevProtocol.makePresence(PresenceStatus.OFFLINE))
            sendRaw(conn, BarevProtocol.makeStreamEnd())
        }
        cleanupConn(conn)
    }

    private fun cleanupConn(conn: BuddyConnection) {
        conn.isConnected       = false
        conn.streamEstablished = false
        conn.status            = PresenceStatus.OFFLINE
        conn.isTyping          = false
        try { conn.writer?.close() } catch (_: Exception) {}
        try { conn.reader?.close() } catch (_: Exception) {}
        try { conn.socket?.close() } catch (_: Exception) {}
        conn.writer = null
        conn.reader = null
        conn.socket = null
        listener?.onConnectionStateChanged(conn.nick)
        listener?.onStatusChanged(conn.nick)
    }

    private fun startReadLoop(conn: BuddyConnection, initialBuffer: StringBuilder) {
        thread {
            val sb = initialBuffer
            try {
                val buf = CharArray(1024)
                while (conn.isConnected) {
                    val n = conn.reader?.read(buf) ?: break
                    if (n == -1) break
                    conn.lastActivityTime = System.currentTimeMillis()
                    val chunk = String(buf, 0, n)
                    debugLog("RAW IN [${conn.nick}]: $chunk")
                    sb.append(chunk)
                    processBuffer(conn, sb)
                }
            } catch (e: Exception) {
                if (conn.isConnected) debugLog("Read error [${conn.nick}]: ${e.message}")
            } finally {
                if (conn.isConnected) {
                    addSystemMessage(conn.nick, "Connection closed")
                    cleanupConn(conn)
                }
            }
        }

        thread {
            while (conn.isConnected) {
                try {
                    Thread.sleep(20_000)
                    if (!conn.isConnected || !conn.streamEstablished) continue
                    val id = BarevProtocol.pingId()
                    sendRaw(conn, BarevProtocol.makePing(localId, conn.peerId, id))
                    debugLog("Keepalive ping sent to ${conn.nick}")
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun processBuffer(conn: BuddyConnection, sb: StringBuilder) {
        val text = sb.toString()
        if (text.isBlank()) return

        for (tag in listOf("</message>", "</presence>", "</iq>", "</stream:stream>")) {
            val idx = text.indexOf(tag)
            if (idx != -1) {
                val end        = idx + tag.length
                val stanzaText = text.substring(0, end).trim()
                if (stanzaText.isNotEmpty()) {
                    debugLog("STANZA [${conn.nick}]: $stanzaText")
                    handleStanza(conn, stanzaText)
                }
                sb.delete(0, end)
                if (sb.isNotBlank()) processBuffer(conn, sb)
                return
            }
        }

        for (tag in listOf("<presence/>", "<presence type=\"unavailable\"/>")) {
            val idx = text.indexOf(tag)
            if (idx != -1) {
                val end = idx + tag.length
                debugLog("STANZA [${conn.nick}]: $tag")
                handleStanza(conn, tag)
                sb.delete(0, end)
                if (sb.isNotBlank()) processBuffer(conn, sb)
                return
            }
        }

        if (text.contains("stream:stream")) {
            val streamIdx = text.indexOf("stream:stream")
            val gtIdx     = text.indexOf(">", streamIdx)
            if (gtIdx != -1) {
                val stanzaText = text.substring(0, gtIdx + 1).trim()
                debugLog("STREAM [${conn.nick}]: $stanzaText")
                handleStanza(conn, stanzaText)
                sb.delete(0, gtIdx + 1)
                if (sb.isNotBlank()) processBuffer(conn, sb)
            }
        }
    }

    private fun handleStanza(conn: BuddyConnection, raw: String) {
        debugLog("STANZA [${conn.nick}]: $raw")

        val wasTyping = conn.isTyping
        when {
            raw.contains("<composing") && !raw.contains("<body>") -> conn.isTyping = true
            raw.contains("<paused")   || raw.contains("<active")  -> conn.isTyping = false
            raw.contains("<body>")                                 -> conn.isTyping = false
        }
        if (conn.isTyping != wasTyping) listener?.onTypingChanged(conn.nick)

        when (val stanza = BarevProtocol.parseStanza(raw)) {

            is ParsedStanza.StreamStart -> {
                if (!conn.isInitiator) sendStreamStart(conn)
                conn.streamEstablished = true
                sendPresenceNow(conn)
                addSystemMessage(conn.nick, "Connected to ${stanza.from}")
                listener?.onConnectionStateChanged(conn.nick)
                listener?.onStatusChanged(conn.nick)
            }

            is ParsedStanza.StreamEnd -> {
                addSystemMessage(conn.nick, "Peer closed the stream")
                cleanupConn(conn)
            }

            is ParsedStanza.PresenceUpdate -> {
                conn.status = stanza.status
                val label = when (stanza.status) {
                    PresenceStatus.AVAILABLE -> "online"
                    PresenceStatus.AWAY      -> "away"
                    PresenceStatus.DND       -> "do not disturb"
                    else                     -> "online"
                }
                val suffix = if (stanza.statusText.isNotEmpty()) " – ${stanza.statusText}" else ""
                addSystemMessage(conn.nick, "Peer is $label$suffix")
                listener?.onStatusChanged(conn.nick)
            }

            is ParsedStanza.PresenceOffline -> {
                conn.status = PresenceStatus.OFFLINE
                addSystemMessage(conn.nick, "Peer went offline")
                listener?.onStatusChanged(conn.nick)
            }

            is ParsedStanza.Message -> {
                val sender = if (stanza.from.isNotEmpty()) stanza.from.substringBefore("@") else conn.nick
                conn.messages.add(ChatMessage(timestamp(), sender, stanza.body))
                listener?.onMessageReceived(conn.nick)
            }

            is ParsedStanza.Ping -> {
                sendRaw(conn, BarevProtocol.makePong(localId, conn.peerId, stanza.id))
                debugLog("Ping received from ${conn.nick}, pong sent")
            }

            is ParsedStanza.Pong ->
                conn.lastActivityTime = System.currentTimeMillis()

            is ParsedStanza.FileOffer ->
                addSystemMessage(conn.nick, "${stanza.from.substringBefore("@")} wants to send: ${stanza.fileName} (${stanza.fileSize} bytes)")

            else -> debugLog("Unhandled [${conn.nick}]: $raw")
        }
    }

    fun sendMessage(nick: String, body: String) {
        val conn = connections[nick] ?: return
        if (!conn.streamEstablished) return
        sendRaw(conn, BarevProtocol.makeChatMessage(conn.peerId, body))
        val myNick = localId.substringBefore("@")
        conn.messages.add(ChatMessage(timestamp(), myNick, body))
        listener?.onMessageReceived(nick)
    }

    fun sendPresence(nick: String, status: PresenceStatus) {
        val conn = connections[nick] ?: return
        if (!conn.streamEstablished) return
        sendRaw(conn, BarevProtocol.makePresence(status, ""))
    }

    fun sendPresenceToAll(status: PresenceStatus) {
        connections.values.filter { it.streamEstablished }.forEach {
            sendRaw(it, BarevProtocol.makePresence(status, ""))
        }
    }

    private fun sendPresenceNow(conn: BuddyConnection) {
        sendRaw(conn, BarevProtocol.makePresence(PresenceStatus.AVAILABLE, ""))
    }

    private fun sendStreamStart(conn: BuddyConnection) {
        sendRaw(conn, BarevProtocol.makeStreamStart(localId, conn.peerId))
    }

    private fun sendRaw(conn: BuddyConnection, data: String) {
        thread {
            try {
                conn.writer?.write(data)
                conn.writer?.newLine()
                conn.writer?.flush()
                conn.lastActivityTime = System.currentTimeMillis()
                debugLog("RAW OUT [${conn.nick}]: $data")
            } catch (e: Exception) {
                debugLog("Send error [${conn.nick}]: ${e.message}")
            }
        }
    }

    fun addBuddy(contact: Contact) {
        if (!connections.containsKey(contact.nick)) {
            connections[contact.nick] = BuddyConnection(
                nick = contact.nick,
                ipv6 = contact.ipv6,
                port = contact.port
            )
        }
    }

    fun removeBuddy(nick: String) {
        disconnectBuddy(nick)
        connections.remove(nick)
    }

    private fun addSystemMessage(nick: String, text: String) {
        connections[nick]?.messages?.add(
            ChatMessage(timestamp(), "", text, isSystem = true)
        )
        listener?.onMessageReceived(nick)
    }

    private fun debugLog(msg: String) { Log.d("BarevService", msg) }
}