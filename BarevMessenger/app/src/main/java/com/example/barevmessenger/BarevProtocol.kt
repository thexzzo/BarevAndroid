package com.example.barevmessenger

object BarevProtocol {

    fun makeStreamStart(from: String, to: String): String {
        return "<stream:stream from=\"$from\" to=\"$to\" version=\"1.0\">"
    }

    fun makePresence(from: String): String {
        return "<presence from=\"$from\"/>"
    }

    fun makeUnavailablePresence(from: String): String {
        return "<presence from=\"$from\" type=\"unavailable\"/>"
    }

    fun makeChatMessage(from: String, to: String, body: String): String {
        return "<message from=\"$from\" to=\"$to\" type=\"chat\"><body>$body</body></message>"
    }

    fun makeComposing(from: String, to: String): String {
        return "<composing from=\"$from\" to=\"$to\"/>"
    }

    fun makePaused(from: String, to: String): String {
        return "<paused from=\"$from\" to=\"$to\"/>"
    }

    fun parseStanza(stanza: String): ParsedStanza {
        val trimmed = stanza.trim()

        return when {
            trimmed.startsWith("<stream:stream") -> {
                ParsedStanza.StreamStart
            }

            trimmed.startsWith("<presence") -> {
                if (trimmed.contains("type=\"unavailable\"")) {
                    ParsedStanza.PresenceOffline
                } else {
                    ParsedStanza.PresenceOnline
                }
            }

            trimmed.startsWith("<message") -> {
                val body = extractTagValue(trimmed, "body")
                if (body != null) {
                    ParsedStanza.Message(body)
                } else {
                    ParsedStanza.Raw(trimmed)
                }
            }

            trimmed.startsWith("<composing") -> {
                ParsedStanza.Typing
            }

            trimmed.startsWith("<paused") -> {
                ParsedStanza.Paused
            }

            else -> ParsedStanza.Raw(trimmed)
        }
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        val startTag = "<$tagName>"
        val endTag = "</$tagName>"

        val start = xml.indexOf(startTag)
        val end = xml.indexOf(endTag)

        return if (start != -1 && end != -1 && end > start) {
            xml.substring(start + startTag.length, end)
        } else {
            null
        }
    }
}

sealed class ParsedStanza {
    data object StreamStart : ParsedStanza()
    data object PresenceOnline : ParsedStanza()
    data object PresenceOffline : ParsedStanza()
    data class Message(val body: String) : ParsedStanza()
    data object Typing : ParsedStanza()
    data object Paused : ParsedStanza()
    data class Raw(val content: String) : ParsedStanza()
}