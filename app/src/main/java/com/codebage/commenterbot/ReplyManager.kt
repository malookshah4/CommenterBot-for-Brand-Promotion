package com.codebage.commenterbot

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ReplyItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val enabledForBot: Boolean = true
)

object ReplyManager {
    private const val PREFS_NAME = "reply_prefs"
    private const val KEY_REPLIES = "replies_json"
    private const val KEY_SEEDED = "seeded_from_defaults"

    val replies = mutableStateListOf<ReplyItem>()
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    fun init(context: Context, defaultReplies: List<String>) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            // First launch: seed from strings.xml defaults
            replies.clear()
            defaultReplies.forEach { replies.add(ReplyItem(text = it)) }
            save()
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        } else {
            load()
        }
        initialized = true
    }

    fun addReply(text: String) {
        if (text.isBlank()) return
        replies.add(ReplyItem(text = text.trim()))
        save()
    }

    fun updateReply(id: String, newText: String) {
        val idx = replies.indexOfFirst { it.id == id }
        if (idx >= 0 && newText.isNotBlank()) {
            replies[idx] = replies[idx].copy(text = newText.trim())
            save()
        }
    }

    fun deleteReply(id: String) {
        replies.removeAll { it.id == id }
        save()
    }

    fun toggleBotEnabled(id: String) {
        val idx = replies.indexOfFirst { it.id == id }
        if (idx >= 0) {
            replies[idx] = replies[idx].copy(enabledForBot = !replies[idx].enabledForBot)
            save()
        }
    }

    /** Returns only the replies that are enabled for bot use */
    fun getBotReplies(): List<String> = replies.filter { it.enabledForBot }.map { it.text }

    private fun save() {
        val arr = JSONArray()
        replies.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("text", r.text)
                put("enabled", r.enabledForBot)
            })
        }
        prefs.edit().putString(KEY_REPLIES, arr.toString()).apply()
    }

    private fun load() {
        val json = prefs.getString(KEY_REPLIES, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            replies.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                replies.add(
                    ReplyItem(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        enabledForBot = obj.optBoolean("enabled", true)
                    )
                )
            }
        } catch (_: Exception) {
            replies.clear()
        }
    }
}
