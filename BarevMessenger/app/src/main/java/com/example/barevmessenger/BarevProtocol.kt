package com.example.barevmessenger

enum class PresenceStatus {
    AVAILABLE, AWAY, DND, OFFLINE
}

object BarevProtocol {





    fun makeStreamStart(from: String, to: String): String =
        "<stream from=\"$from\" to=\"$to\" version=\"1.0\"/>"






    fun makePresence(
        from: String,
        to: String = "",
        status: PresenceStatus = PresenceStatus.AVAILABLE,
        statusText: String = ""
    ): String {
        if (status == PresenceStatus.OFFLINE) {
            return makeUnavailablePresence(from, to)
        }

        val toAttr = if (to.isNotEmpty()) " to=\"$to\"" else ""
        val showTag = when (status) {
            PresenceStatus.AWAY -> "<show>away</show>"
            PresenceStatus.DND  -> "<show>dnd</show>"
            else                -> ""
        }
        val statusTag = if (statusText.isNotEmpty()) "<status>${escape(statusText)}</status>" else ""

        return "<presence from=\"$from\"$toAttr>$showTag$statusTag</presence>"
    }

    fun makeUnavailablePresence(from: String, to: String = ""): String {
        val toAttr = if (to.isNotEmpty()) " to=\"$to\"" else ""
        return "<presence from=\"$from\"$toAttr type=\"unavailable\"/>"
    }





    fun makeChatMessage(from: String, to: String, body: String): String =
        "<message from=\"$from\" to=\"$to\" type=\"chat\"><body>${escape(body)}</body></message>"





    fun makeComposing(from: String, to: String): String =
        "<message from=\"$from\" to=\"$to\" type=\"chat\">" +
                "<composing xmlns=\"http://jabber.org/protocol/chatstates\"/>" +
                "</message>"

    fun makePaused(from: String, to: String): String =
        "<message from=\"$from\" to=\"$to\" type=\"chat\">" +
                "<paused xmlns=\"http://jabber.org/protocol/chatstates\"/>" +
                "</message>"





    fun makePing(): String = "<ping/>"
    fun makePong(): String = "<pong/>"








    fun makeVCardRequest(from: String, to: String): String =
        "<iq from=\"$from\" to=\"$to\" type=\"get\" id=\"vc1\"><vCard xmlns=\"vcard-temp\"/></iq>"


    fun makeVCardUpdate(from: String, to: String, base64Data: String): String =
        "<iq from=\"$from\" to=\"$to\" type=\"set\" id=\"vc2\">" +
                "<vCard xmlns=\"vcard-temp\">" +
                "<PHOTO><TYPE>image/jpeg</TYPE><BINVAL>$base64Data</BINVAL></PHOTO>" +
                "</vCard></iq>"









    fun makeFileOffer(
        from: String,
        to: String,
        sid: String,
        fileName: String,
        fileSize: Long,
        mimeType: String = "application/octet-stream"
    ): String =
        "<iq from=\"$from\" to=\"$to\" type=\"set\" id=\"ft_$sid\">" +
                "<si xmlns=\"http://jabber.org/protocol/si\" id=\"$sid\" mime-type=\"$mimeType\">" +
                "<file xmlns=\"http://jabber.org/protocol/si/profile/file-transfer\"" +
                " name=\"${escape(fileName)}\" size=\"$fileSize\"/>" +
                "</si></iq>"

    fun makeFileAccept(from: String, to: String, sid: String): String =
        "<iq from=\"$from\" to=\"$to\" type=\"result\" id=\"ft_$sid\">" +
                "<si xmlns=\"http://jabber.org/protocol/si\" id=\"$sid\"/></iq>"

    fun makeFileReject(from: String, to: String, sid: String): String =
        "<iq from=\"$from\" to=\"$to\" type=\"error\" id=\"ft_$sid\">" +
                "<error type=\"cancel\"><forbidden/></error></iq>"





    fun parseStanza(stanza: String): ParsedStanza {
        val t = stanza.trim()

        return when {
            t.startsWith("<stream")     -> ParsedStanza.StreamStart(
                extractAttr(t, "from")
            )

            t.startsWith("<presence") && t.contains("type=\"unavailable\"") ->
                ParsedStanza.PresenceOffline(extractAttr(t, "from"))

            t.startsWith("<presence") -> {
                val from       = extractAttr(t, "from")
                val show       = extractTagValue(t, "show")
                val statusText = unescape(extractTagValue(t, "status") ?: "")
                when (show) {
                    "away" -> ParsedStanza.PresenceAway(from, statusText)
                    "dnd"  -> ParsedStanza.PresenceDnd(from, statusText)
                    else   -> ParsedStanza.PresenceOnline(from, statusText)
                }
            }

            t.startsWith("<message") && t.contains("<body>") -> {
                val from = extractAttr(t, "from")
                val body = unescape(extractTagValue(t, "body") ?: "")
                ParsedStanza.Message(from, body)
            }

            t.contains("<composing")    -> ParsedStanza.Typing(extractAttr(t, "from"))
            t.contains("<paused")       -> ParsedStanza.Paused(extractAttr(t, "from"))

            t.startsWith("<ping")       -> ParsedStanza.Ping
            t.startsWith("<pong")       -> ParsedStanza.Pong


            t.startsWith("<iq") && t.contains("<BINVAL>") -> {
                val from    = extractAttr(t, "from")
                val binval  = extractTagValue(t, "BINVAL") ?: ""
                ParsedStanza.Avatar(from, binval)
            }


            t.startsWith("<iq") && t.contains("type=\"get\"") && t.contains("vCard") ->
                ParsedStanza.VCardRequest(extractAttr(t, "from"))


            t.startsWith("<iq") && t.contains("type=\"set\"") && t.contains("<si ") -> {
                val from     = extractAttr(t, "from")
                val sid      = extractAttr(t, "id").removePrefix("ft_")
                val fileName = unescape(extractAttr(t, "name"))
                val fileSize = extractAttr(t, "size").toLongOrNull() ?: 0L
                ParsedStanza.FileOffer(from, sid, fileName, fileSize)
            }


            t.startsWith("<iq") && t.contains("type=\"result\"") && t.contains("<si ") -> {
                val from = extractAttr(t, "from")
                val sid  = extractAttr(t, "id").removePrefix("ft_")
                ParsedStanza.FileAccept(from, sid)
            }


            t.startsWith("<iq") && t.contains("type=\"error\"") -> {
                val from = extractAttr(t, "from")
                val sid  = extractAttr(t, "id").removePrefix("ft_")
                ParsedStanza.FileReject(from, sid)
            }

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
            else             -> ""
        }
    }
}

sealed class ParsedStanza {
    data class  StreamStart(val from: String)                             : ParsedStanza()


    data class  PresenceOnline(val from: String, val statusText: String)  : ParsedStanza()
    data class  PresenceAway(val from: String, val statusText: String)    : ParsedStanza()
    data class  PresenceDnd(val from: String, val statusText: String)     : ParsedStanza()
    data class  PresenceOffline(val from: String)                         : ParsedStanza()


    data class  Message(val from: String, val body: String)               : ParsedStanza()


    data class  Typing(val from: String)                                  : ParsedStanza()
    data class  Paused(val from: String)                                  : ParsedStanza()


    data object Ping                                                       : ParsedStanza()
    data object Pong                                                       : ParsedStanza()


    data class  Avatar(val from: String, val base64Jpeg: String)          : ParsedStanza()
    data class  VCardRequest(val from: String)                            : ParsedStanza()


    data class  FileOffer(val from: String, val sid: String,
                          val fileName: String, val fileSize: Long)        : ParsedStanza()
    data class  FileAccept(val from: String, val sid: String)             : ParsedStanza()
    data class  FileReject(val from: String, val sid: String)             : ParsedStanza()


    data class  Raw(val content: String)                                  : ParsedStanza()
}