package com.example.barevmessenger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val nick: String, val ipv6: String, val port: Int = 1337)

object ContactManager {

    private const val PREFS_NAME   = "barev_contacts"
    private const val KEY_CONTACTS = "contacts"

    fun load(context: Context): MutableList<Contact> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(KEY_CONTACTS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list  = mutableListOf<Contact>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Contact(
                nick = obj.getString("nick"),
                ipv6 = obj.getString("ipv6"),
                port = obj.optInt("port", 1337)
            ))
        }
        return list
    }

    fun save(context: Context, contacts: List<Contact>) {
        val array = JSONArray()
        for (c in contacts) {
            val obj = JSONObject()
            obj.put("nick", c.nick)
            obj.put("ipv6", c.ipv6)
            obj.put("port", c.port)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTACTS, array.toString())
            .apply()
    }

    fun add(context: Context, nick: String, ipv6: String, port: Int = 1337): MutableList<Contact> {
        val contacts = load(context)
        contacts.removeAll { it.nick == nick }
        contacts.add(Contact(nick, ipv6, port))
        save(context, contacts)
        return contacts
    }

    fun remove(context: Context, nick: String): MutableList<Contact> {
        val contacts = load(context)
        contacts.removeAll { it.nick == nick }
        save(context, contacts)
        return contacts
    }
}
