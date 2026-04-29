package com.example.barevmessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
    var currentStatus: PresenceStatus = PresenceStatus.AVAILABLE
    var listener: ServiceListener? = null
    var appContext: Context? = null
    private var globalServerSocket: ServerSocket? = null

    interface ServiceListener {
        fun onMessageReceived(nick: String)
        fun onStatusChanged(nick: String)
        fun onConnectionStateChanged(nick: String)
        fun onTypingChanged(nick: String)
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        currentStatus = loadStatus()
        startForegroundNotification()
    }

    private fun loadStatus(): PresenceStatus {
        val prefs = getSharedPreferences("barev_prefs", Context.MODE_PRIVATE)
        return when (prefs.getString("status", "AVAILABLE")) {
            "AWAY" -> PresenceStatus.AWAY
            "DND"  -> PresenceStatus.DND
            else   -> PresenceStatus.AVAILABLE
        }
    }

    private fun saveStatus(status: PresenceStatus) {
        getSharedPreferences("barev_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("status", status.name)
            .apply()
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
                    try {
                        val s = globalServerSocket?.accept() ?: break
                        debugLog("Incoming from ${s.inetAddress.hostAddress}")
                        handleIncomingSocket(s)
                    } catch (e: Exception) {
                        debugLog("Accept error: ${e.message}")
                        if (globalServerSocket?.isClosed == true) break
                    }
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

                        if (conn.isConnected) {
                            debugLog("Replacing stale connection for ${conn.nick}")
                            conn.isConnected = false
                            try { conn.writer?.close() } catch (_: Exception) {}
                            try { conn.reader?.close() } catch (_: Exception) {}
                            try { conn.socket?.close() } catch (_: Exception) {}
                        }

                        conn.socket            = s
                        conn.writer            = writer
                        conn.reader            = reader
                        conn.isConnected       = true
                        conn.isInitiator       = false
                        conn.streamEstablished = false
                        conn.lastActivityTime  = System.currentTimeMillis()
                        listener?.onConnectionStateChanged("${conn.nick}@${conn.ipv6}")
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
                val s = Socket()
                s.connect(java.net.InetSocketAddress(conn.ipv6, conn.port), 3000)
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
        val key = "${conn.nick}@${conn.ipv6}"
        listener?.onConnectionStateChanged(key)
        listener?.onStatusChanged(key)
    }

    private fun startReadLoop(conn: BuddyConnection, initialBuffer: StringBuilder) {
        thread {
            val sb = initialBuffer
            try {
                val buf = CharArray(1024)
                while (conn.isConnected) {
                    val n = try {
                        conn.reader?.read(buf) ?: break
                    } catch (e: Exception) {
                        debugLog("Read error [${conn.nick}]: ${e.message}")
                        break
                    }
                    if (n == -1) break
                    conn.lastActivityTime = System.currentTimeMillis()
                    val chunk = String(buf, 0, n)
                    debugLog("RAW IN [${conn.nick}]: $chunk")
                    sb.append(chunk)
                    try { processBuffer(conn, sb) } catch (e: Exception) {
                        debugLog("processBuffer error [${conn.nick}]: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                debugLog("Read loop crashed [${conn.nick}]: ${e.message}")
            } finally {
                debugLog("Connection dropped for ${conn.nick}, cleaning up")
                cleanupConn(conn)
            }
        }
    }

    private fun processBuffer(conn: BuddyConnection, sb: StringBuilder) {
        val text = sb.toString()
        if (text.isBlank()) return

        for (closeTag in listOf("</message>", "</presence>", "</iq>", "</stream:stream>")) {
            val closeIdx = text.indexOf(closeTag)
            if (closeIdx != -1) {
                val openTag = closeTag.replace("/", "").replace(">", "")
                val openIdx = text.lastIndexOf(openTag, closeIdx).takeIf { it != -1 } ?: 0
                val end = closeIdx + closeTag.length
                val stanzaText = text.substring(openIdx, end).trim()
                if (stanzaText.isNotEmpty()) {
                    debugLog("STANZA [${conn.nick}]: $stanzaText")
                    handleStanza(conn, stanzaText)
                }
                sb.delete(0, end)
                if (sb.isNotBlank()) processBuffer(conn, sb)
                return
            }
        }

        if (text.contains("<presence") && !text.contains("</presence>")) {
            val presIdx = text.indexOf("<presence")
            val gtIdx   = text.indexOf(">", presIdx)
            if (gtIdx != -1 && text[gtIdx - 1] == '/') {
                val end        = gtIdx + 1
                val stanzaText = text.substring(presIdx, end).trim()
                debugLog("STANZA [${conn.nick}]: $stanzaText")
                handleStanza(conn, stanzaText)
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
        val key = "${conn.nick}@${conn.ipv6}"

        val wasTyping = conn.isTyping
        when {
            raw.contains("<composing") && !raw.contains("<body>") -> conn.isTyping = true
            raw.contains("<paused")   || raw.contains("<active")  -> conn.isTyping = false
            raw.contains("<body>")                                 -> conn.isTyping = false
        }
        if (conn.isTyping != wasTyping) listener?.onTypingChanged(key)

        when (val stanza = BarevProtocol.parseStanza(raw)) {

            is ParsedStanza.StreamStart -> {
                if (!conn.isInitiator) sendStreamStart(conn)
                conn.streamEstablished = true
                sendPresenceNow(conn)
                addSystemMessage(key, "Connected to ${stanza.from}")
                listener?.onConnectionStateChanged(key)
                listener?.onStatusChanged(key)
            }

            is ParsedStanza.StreamEnd -> {
                addSystemMessage(key, "Peer closed the stream")
                cleanupConn(conn)
            }

            is ParsedStanza.PresenceUpdate -> {
                val hasContent = raw.contains("<show>") || raw.contains("<status>") || raw.contains("<x ")
                if (stanza.status == PresenceStatus.AVAILABLE && !hasContent && conn.status != PresenceStatus.OFFLINE) {
                    debugLog("Ignoring bare presence keepalive from ${conn.nick}")
                } else {
                    conn.status = stanza.status
                    val label = when (stanza.status) {
                        PresenceStatus.AVAILABLE -> "online"
                        PresenceStatus.AWAY      -> "away"
                        PresenceStatus.DND       -> "do not disturb"
                        else                     -> "online"
                    }
                    val suffix = if (stanza.statusText.isNotEmpty()) " – ${stanza.statusText}" else ""
                    addSystemMessage(key, "Peer is $label$suffix")
                    listener?.onStatusChanged(key)
                }
            }

            is ParsedStanza.PresenceOffline -> {
                conn.status = PresenceStatus.OFFLINE
                addSystemMessage(key, "Peer went offline")
                listener?.onStatusChanged(key)
            }

            is ParsedStanza.Message -> {
                val sender = if (stanza.from.isNotEmpty()) stanza.from.substringBefore("@") else conn.nick
                conn.messages.add(ChatMessage(timestamp(), sender, stanza.body))
                saveMessages(conn)
                listener?.onMessageReceived(key)
            }

            is ParsedStanza.Ping -> {
                sendRaw(conn, BarevProtocol.makePong(localId, conn.peerId, stanza.id))
                debugLog("Ping received from ${conn.nick}, pong sent")
            }

            is ParsedStanza.Pong ->
                conn.lastActivityTime = System.currentTimeMillis()

            is ParsedStanza.FileOffer ->
                addSystemMessage(key, "${stanza.from.substringBefore("@")} wants to send: ${stanza.fileName} (${stanza.fileSize} bytes)")

            else -> debugLog("Unhandled [${conn.nick}]: $raw")
        }
    }

    fun sendComposing(key: String) {
        val conn = connections[key] ?: return
        if (!conn.streamEstablished) return
        sendRaw(conn, BarevProtocol.makeComposing(localId, conn.peerId))
    }

    fun sendPaused(key: String) {
        val conn = connections[key] ?: return
        if (!conn.streamEstablished) return
        sendRaw(conn, BarevProtocol.makePaused(localId, conn.peerId))
    }

    fun sendMessage(nick: String, body: String) {
        val conn = connections[nick] ?: return
        if (!conn.streamEstablished) return
        sendRaw(conn, BarevProtocol.makeChatMessage(conn.peerId, body))
        val myNick = localId.substringBefore("@")
        conn.messages.add(ChatMessage(timestamp(), myNick, body))
        saveMessages(conn)
        listener?.onMessageReceived(nick)
    }

    fun sendPresence(nick: String, status: PresenceStatus) {
        val conn = connections[nick] ?: return
        if (!conn.streamEstablished) return
        sendRaw(conn, BarevProtocol.makePresence(status, ""))
    }

    fun sendPresenceToAll(status: PresenceStatus) {
        currentStatus = status
        saveStatus(status)
        connections.values.filter { it.streamEstablished }.forEach {
            sendRaw(it, BarevProtocol.makePresence(status, ""))
        }
    }

    private fun sendPresenceNow(conn: BuddyConnection) {
        sendRaw(conn, BarevProtocol.makePresence(currentStatus, ""))
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
        val key = "${contact.nick}@${contact.ipv6}"
        if (!connections.containsKey(key)) {
            val messages = appContext?.let { MessageStore.load(it, key) } ?: mutableListOf()
            connections[key] = BuddyConnection(
                nick     = contact.nick,
                ipv6     = contact.ipv6,
                port     = contact.port,
                messages = messages
            )
        }
    }

    fun removeBuddy(nick: String) {
        disconnectBuddy(nick)
        connections.remove(nick)
        appContext?.let { MessageStore.clear(it, nick) }
    }

    private fun addSystemMessage(nick: String, text: String) {
        connections[nick]?.messages?.add(
            ChatMessage(timestamp(), "", text, isSystem = true)
        )
        listener?.onMessageReceived(nick)
    }

    private fun saveMessages(conn: BuddyConnection) {
        val key = "${conn.nick}@${conn.ipv6}"
        appContext?.let { MessageStore.save(it, key, conn.messages) }
    }

    private fun debugLog(msg: String) { Log.d("BarevService", msg) }
}
