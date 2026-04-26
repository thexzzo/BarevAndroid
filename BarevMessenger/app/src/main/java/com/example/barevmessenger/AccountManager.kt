package com.example.barevmessenger

import android.content.Context

data class Account(val nick: String, val ipv6: String, val port: Int = 1337) {
    val jid get() = if (nick.isNotEmpty() && ipv6.isNotEmpty()) "$nick@$ipv6" else ""
}

object AccountManager {

    private const val PREFS_NAME = "barev_account"
    private const val KEY_NICK   = "nick"
    private const val KEY_IPV6   = "ipv6"
    private const val KEY_PORT   = "port"

    fun load(context: Context): Account {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Account(
            nick = prefs.getString(KEY_NICK, "") ?: "",
            ipv6 = prefs.getString(KEY_IPV6, "") ?: "",
            port = prefs.getInt(KEY_PORT, 1337)
        )
    }

    fun save(context: Context, nick: String, ipv6: String, port: Int = 1337) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NICK, nick)
            .putString(KEY_IPV6, ipv6)
            .putInt(KEY_PORT, port)
            .apply()
    }
}