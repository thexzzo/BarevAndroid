package com.example.barevmessenger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MessageStore {

    private const val PREFS_NAME = "barev_messages"

    private fun keyFor(nick: String) = "messages_$nick"

    fun load(context: Context, nick: String): MutableList<ChatMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json  = prefs.getString(keyFor(nick), "[]") ?: "[]"
        val array = JSONArray(json)
        val list  = mutableListOf<ChatMessage>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(ChatMessage(
                timestamp = obj.getString("timestamp"),
                sender    = obj.getString("sender"),
                body      = obj.getString("body"),
                isSystem  = obj.optBoolean("isSystem", false)
            ))
        }
        return list
    }

    fun save(context: Context, nick: String, messages: List<ChatMessage>) {
        val array = JSONArray()
        val recent = if (messages.size > 200) messages.takeLast(200) else messages
        for (m in recent) {
            val obj = JSONObject()
            obj.put("timestamp", m.timestamp)
            obj.put("sender",    m.sender)
            obj.put("body",      m.body)
            obj.put("isSystem",  m.isSystem)
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(keyFor(nick), array.toString())
            .apply()
    }

    fun clear(context: Context, nick: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyFor(nick))
            .apply()
    }
}