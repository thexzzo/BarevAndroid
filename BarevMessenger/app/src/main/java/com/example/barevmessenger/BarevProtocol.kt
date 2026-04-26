package com.example.barevmessenger

enum class PresenceStatus { AVAILABLE, AWAY, DND, OFFLINE }

object BarevProtocol {

    fun makeStreamStart(from: String, to: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>" +
                "<stream:stream xmlns=\"jabber:client\" " +
                "xmlns:stream=\"http://etherx.jabber.org/streams\" " +
                "from=\"$from\" to=\"$to\">"

    fun makeStreamEnd(): String = "</stream:stream>"

    fun makePresence(
        status: PresenceStatus = PresenceStatus.AVAILABLE,
        statusText: String = ""
    ): String = when (status) {
        PresenceStatus.OFFLINE -> "<presence type=\"unavailable\"/>"
        PresenceStatus.AVAILABLE -> {
            if (statusText.isNotEmpty())
                "<presence><status>${escape(statusText)}</status></presence>"
            else "<presence/>"
        }
        else -> {
            val showVal = when (status) {
                PresenceStatus.AWAY -> "away"
                PresenceStatus.DND  -> "dnd"
                else -> "away"
            }
            val statusTag = if (statusText.isNotEmpty())
                "<status>${escape(statusText)}</status>" else ""
            "<presence><show>$showVal</show>$statusTag</presence>"
        }
    }

    fun makeChatMessage(to: String, body: String): String =
        "<message to=\"$to\" type=\"chat\"><body>${escape(body)}</body></message>"

    fun makeComposing(from: String, to: String): String =
        "<message from=\"$from\" to=\"$to\" type=\"chat\">" +
                "<composing xmlns=\"http://jabber.org/protocol/chatstates\"/>" +
                "</message>"

    fun makePaused(from: String, to: String): String =
        "<message from=\"$from\" to=\"$to\" type=\"chat\">" +
                "<paused xmlns=\"http://jabber.org/protocol/chatstates\"/>" +
                "</message>"

    fun makePing(from: String, to: String, id: String): String =
        "<iq type=\"get\" id=\"$id\" from=\"$from\" to=\"$to\">" +
                "<ping xmlns=\"urn:xmpp:ping\"/></iq>"

    fun makePong(from: String, to: String, id: String): String =
        "<iq type=\"result\" id=\"$id\" to=\"$to\" from=\"$from\"/>"

    fun makeFileOffer(
        from: String, to: String,
        sid: String, fileName: String, fileSize: Long,
        mimeType: String = "application/octet-stream"
    ): String =
        "<iq type=\"set\" id=\"si_$sid\" to=\"$to\" from=\"$from\">" +
                "<si xmlns=\"http://jabber.org/protocol/si\" id=\"$sid\" " +
                "mime-type=\"$mimeType\" " +
                "profile=\"http://jabber.org/protocol/si/profile/file-transfer\">" +
                "<file xmlns=\"http://jabber.org/protocol/si/profile/file-transfer\" " +
                "name=\"${escape(fileName)}\" size=\"$fileSize\"/>" +
                "<feature xmlns=\"http://jabber.org/protocol/feature-neg\">" +
                "<x xmlns=\"jabber:x:data\" type=\"form\">" +
                "<field var=\"stream-method\" type=\"list-single\">" +
                "<option><value>http://jabber.org/protocol/bytestreams</value></option>" +
                "</field></x></feature></si></iq>"

    fun makeFileAccept(from: String, to: String, sid: String): String =
        "<iq type=\"result\" id=\"si_$sid\" to=\"$to\" from=\"$from\">" +
                "<si xmlns=\"http://jabber.org/protocol/si\">" +
                "<feature xmlns=\"http://jabber.org/protocol/feature-neg\">" +
                "<x xmlns=\"jabber:x:data\" type=\"submit\">" +
                "<field var=\"stream-method\">" +
                "<value>http://jabber.org/protocol/bytestreams</value>" +
                "</field></x></feature></si></iq>"

    fun makeFileReject(from: String, to: String, sid: String): String =
        "<iq type=\"error\" id=\"si_$sid\" to=\"$to\" from=\"$from\">" +
                "<error code=\"403\" type=\"auth\">" +
                "<forbidden xmlns=\"urn:ietf:params:xml:ns:xmpp-stanzas\"/>" +
                "</e></iq>"

    fun makeStreamhostProposal(
        from: String, to: String,
        sid: String, hostJid: String, hostIp: String, port: Int
    ): String =
        "<iq type=\"set\" id=\"bytestream_$sid\" to=\"$to\" from=\"$from\">" +
                "<query xmlns=\"http://jabber.org/protocol/bytestreams\" sid=\"$sid\">" +
                "<streamhost jid=\"$hostJid\" host=\"$hostIp\" port=\"$port\"/>" +
                "</query></iq>"

    fun makeStreamhostUsed(from: String, to: String, sid: String, hostJid: String): String =
        "<iq type=\"result\" id=\"bytestream_$sid\" to=\"$to\" from=\"$from\">" +
                "<query xmlns=\"http://jabber.org/protocol/bytestreams\" sid=\"$sid\">" +
                "<streamhost-used jid=\"$hostJid\"/>" +
                "</query></iq>"

    fun parseStanza(raw: String): ParsedStanza {
        val t = raw.trim()
        return when {
            t.contains("stream:stream") ->
                ParsedStanza.StreamStart(extractAttr(t, "from"))

            t.contains("</stream:stream>") ->
                ParsedStanza.StreamEnd

            t.startsWith("<presence") && t.contains("type=\"unavailable\"") ->
                ParsedStanza.PresenceOffline

            t.startsWith("<presence") -> {
                val show       = extractTagValue(t, "show") ?: ""
                val statusText = unescape(extractTagValue(t, "status") ?: "")
                val status = when (show) {
                    "away" -> PresenceStatus.AWAY
                    "xa"   -> PresenceStatus.AWAY
                    "dnd"  -> PresenceStatus.DND
                    else   -> PresenceStatus.AVAILABLE
                }
                ParsedStanza.PresenceUpdate(status, statusText)
            }

            t.startsWith("<message") && t.contains("<body>") -> {
                val from = extractAttr(t, "from")
                val body = unescape(extractTagValue(t, "body") ?: "")
                ParsedStanza.Message(from, body)
            }

            t.startsWith("<message") && t.contains("<composing") ->
                ParsedStanza.Typing(extractAttr(t, "from"))

            t.startsWith("<message") && t.contains("<paused") ->
                ParsedStanza.Paused(extractAttr(t, "from"))

            t.startsWith("<message") && t.contains("<active") ->
                ParsedStanza.Raw(t)

            t.startsWith("<message") ->
                ParsedStanza.Raw(t)

            t.contains("<composing") ->
                ParsedStanza.Typing(extractAttr(t, "from"))

            t.contains("<paused") ->
                ParsedStanza.Paused(extractAttr(t, "from"))

            t.startsWith("<iq") && t.contains("<ping") -> {
                val id   = extractAttr(t, "id")
                val from = extractAttr(t, "from")
                ParsedStanza.Ping(id, from)
            }

            t.startsWith("<iq") && t.contains("type=\"result\"") &&
                    !t.contains("<query") && !t.contains("<si") &&
                    extractAttr(t, "id").startsWith("ping") ->
                ParsedStanza.Pong(extractAttr(t, "id"))

            t.startsWith("<iq") && t.contains("type=\"set\"") && t.contains("<si ") -> {
                val sid  = extractAttr(t, "id").removePrefix("si_")
                val from = extractAttr(t, "from")
                val name = unescape(extractAttr(t, "name"))
                val size = extractAttr(t, "size").toLongOrNull() ?: 0L
                ParsedStanza.FileOffer(from, sid, name, size)
            }

            t.startsWith("<iq") && t.contains("type=\"result\"") && t.contains("<si") ->
                ParsedStanza.FileAccept(extractAttr(t, "id").removePrefix("si_"))

            t.startsWith("<iq") && t.contains("type=\"set\"") && t.contains("bytestreams") -> {
                val sid  = extractAttr(t, "sid")
                val from = extractAttr(t, "from")
                val jid  = extractAttr(t, "jid")
                val host = extractAttr(t, "host")
                val port = extractAttr(t, "port").toIntOrNull() ?: 0
                ParsedStanza.StreamhostProposal(sid, from, jid, host, port)
            }

            t.startsWith("<iq") && t.contains("streamhost-used") -> {
                val sid     = extractAttr(t, "sid")
                val usedJid = extractAttr(t, "jid")
                ParsedStanza.StreamhostSelected(sid, usedJid)
            }

            t.startsWith("<iq") && t.contains("type=\"error\"") ->
                ParsedStanza.FileReject(extractAttr(t, "id").removePrefix("si_"))

            else -> ParsedStanza.Raw(t)
        }
    }

    fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun unescape(s: String): String = s
        .replace("&lt;",   "<")
        .replace("&gt;",   ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;",  "&")

    fun extractTagValue(xml: String, tagName: String): String? {
        val open  = "<$tagName>"
        val close = "</$tagName>"
        val start = xml.indexOf(open)
        val end   = xml.indexOf(close)
        return if (start != -1 && end > start) xml.substring(start + open.length, end) else null
    }

    fun extractAttr(xml: String, attr: String): String {
        val dq = "$attr=\""
        val sq = "$attr='"
        return when {
            xml.contains(dq) -> xml.substringAfter(dq).substringBefore("\"")
            xml.contains(sq) -> xml.substringAfter(sq).substringBefore("'")
            else -> ""
        }
    }

    fun isYggdrasilAddress(ip: String): Boolean {
        val first = ip.split(":").firstOrNull() ?: return false
        return try { first.toInt(16) in 0x200..0x3ff } catch (e: Exception) { false }
    }

    fun pingId(): String = "ping-${System.currentTimeMillis()}"
}

sealed class ParsedStanza {
    data class  StreamStart(val from: String)                                         : ParsedStanza()
    data object StreamEnd                                                              : ParsedStanza()
    data class  PresenceUpdate(val status: PresenceStatus, val statusText: String)   : ParsedStanza()
    data object PresenceOffline                                                        : ParsedStanza()
    data class  Message(val from: String, val body: String)                           : ParsedStanza()
    data class  Typing(val from: String)                                              : ParsedStanza()
    data class  Paused(val from: String)                                              : ParsedStanza()
    data class  Ping(val id: String, val from: String)                                : ParsedStanza()
    data class  Pong(val id: String)                                                  : ParsedStanza()
    data class  FileOffer(val from: String, val sid: String,
                          val fileName: String, val fileSize: Long)                   : ParsedStanza()
    data class  FileAccept(val sid: String)                                           : ParsedStanza()
    data class  FileReject(val sid: String)                                           : ParsedStanza()
    data class  StreamhostProposal(val sid: String, val from: String, val jid: String,
                                   val host: String, val port: Int)                   : ParsedStanza()
    data class  StreamhostSelected(val sid: String, val usedJid: String)             : ParsedStanza()
    data class  Raw(val content: String)                                              : ParsedStanza()
}