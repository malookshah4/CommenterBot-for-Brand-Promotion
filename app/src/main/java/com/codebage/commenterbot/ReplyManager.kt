package com.codebage.commenterbot

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class ReplyItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val enabledForBot: Boolean = true
)

data class ProfileItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val replies: List<ReplyItem> = emptyList(),
    val keywords: List<String> = emptyList()
)

data class BotSettings(
    val minDelaySeconds: Int = 3,
    val maxDelaySeconds: Int = 8,
    val maxRepliesPerHour: Int = 0,
    val dailyReplyLimit: Int = 0,
    val autoStopAfterReplies: Int = 0,
    val autoStopAfterMinutes: Int = 0
)

object ReplyManager {
    private const val PREFS_NAME = "reply_prefs"
    private const val KEY_REPLIES = "replies_json"
    private const val KEY_SEEDED = "seeded_from_defaults"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_PROFILE = "active_profile_id"
    private const val KEY_SETTINGS = "bot_settings_json"
    private const val KEY_DAILY_COUNT = "daily_reply_count"
    private const val KEY_DAILY_DATE = "daily_date"
    private const val KEY_MIGRATED_PROFILES = "migrated_to_profiles"

    // ── Observable state ─────────────────────────────────────────────
    val profiles = mutableStateListOf<ProfileItem>()
    val activeProfileId = mutableStateOf("")
    val replies = mutableStateListOf<ReplyItem>()   // Active profile's replies (for UI)
    val botSettings = mutableStateOf(BotSettings())
    val dailyReplies = mutableStateOf(0)

    // ── Internal ─────────────────────────────────────────────────────
    private val replyTimestamps = mutableListOf<Long>()
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    // ── Init ─────────────────────────────────────────────────────────

    fun init(context: Context, defaultReplies: List<String>) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (!prefs.getBoolean(KEY_MIGRATED_PROFILES, false)) {
            migrateToProfiles(defaultReplies)
        } else {
            loadProfiles()
            loadSettings()
        }

        checkDailyReset()
        syncRepliesFromActiveProfile()
        initialized = true
    }

    private fun migrateToProfiles(defaultReplies: List<String>) {
        val existingReplies = mutableListOf<ReplyItem>()

        // Try loading replies from old format
        val oldJson = prefs.getString(KEY_REPLIES, null)
        if (prefs.getBoolean(KEY_SEEDED, false) && oldJson != null) {
            try {
                val arr = JSONArray(oldJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    existingReplies.add(
                        ReplyItem(
                            id = obj.getString("id"),
                            text = obj.getString("text"),
                            enabledForBot = obj.optBoolean("enabled", true)
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        if (existingReplies.isEmpty()) {
            existingReplies.addAll(defaultReplies.map { ReplyItem(text = it) })
        }

        val defaultProfile = ProfileItem(
            name = "Default",
            replies = existingReplies.toList()
        )

        profiles.clear()
        profiles.add(defaultProfile)
        activeProfileId.value = defaultProfile.id

        saveProfiles()
        loadSettings()
        saveSettings()

        prefs.edit()
            .putBoolean(KEY_MIGRATED_PROFILES, true)
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    // ── Profile CRUD ─────────────────────────────────────────────────

    fun addProfile(name: String): ProfileItem {
        val profile = ProfileItem(name = name.trim())
        profiles.add(profile)
        if (profiles.size == 1) {
            activeProfileId.value = profile.id
            syncRepliesFromActiveProfile()
        }
        saveProfiles()
        return profile
    }

    fun deleteProfile(id: String) {
        profiles.removeAll { it.id == id }
        if (activeProfileId.value == id) {
            activeProfileId.value = profiles.firstOrNull()?.id ?: ""
            syncRepliesFromActiveProfile()
        }
        saveProfiles()
    }

    fun renameProfile(id: String, newName: String) {
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx >= 0) {
            profiles[idx] = profiles[idx].copy(name = newName.trim())
            saveProfiles()
        }
    }

    fun setActiveProfile(id: String) {
        activeProfileId.value = id
        syncRepliesFromActiveProfile()
        prefs.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
    }

    fun getActiveProfile(): ProfileItem? =
        profiles.find { it.id == activeProfileId.value }

    fun getActiveKeywords(): List<String> =
        getActiveProfile()?.keywords ?: emptyList()

    // ── Reply CRUD (active profile) ──────────────────────────────────

    fun addReply(text: String) {
        if (text.isBlank()) return
        val idx = profiles.indexOfFirst { it.id == activeProfileId.value }
        if (idx < 0) return
        val profile = profiles[idx]
        profiles[idx] = profile.copy(replies = profile.replies + ReplyItem(text = text.trim()))
        syncRepliesFromActiveProfile()
        saveProfiles()
    }

    fun updateReply(id: String, newText: String) {
        if (newText.isBlank()) return
        val idx = profiles.indexOfFirst { it.id == activeProfileId.value }
        if (idx < 0) return
        val profile = profiles[idx]
        profiles[idx] = profile.copy(
            replies = profile.replies.map {
                if (it.id == id) it.copy(text = newText.trim()) else it
            }
        )
        syncRepliesFromActiveProfile()
        saveProfiles()
    }

    fun deleteReply(id: String) {
        val idx = profiles.indexOfFirst { it.id == activeProfileId.value }
        if (idx < 0) return
        val profile = profiles[idx]
        profiles[idx] = profile.copy(replies = profile.replies.filter { it.id != id })
        syncRepliesFromActiveProfile()
        saveProfiles()
    }

    fun toggleBotEnabled(id: String) {
        val idx = profiles.indexOfFirst { it.id == activeProfileId.value }
        if (idx < 0) return
        val profile = profiles[idx]
        profiles[idx] = profile.copy(
            replies = profile.replies.map {
                if (it.id == id) it.copy(enabledForBot = !it.enabledForBot) else it
            }
        )
        syncRepliesFromActiveProfile()
        saveProfiles()
    }

    /** Returns enabled reply texts from the active profile */
    fun getBotReplies(): List<String> =
        replies.filter { it.enabledForBot }.map { it.text }

    // ── Keyword CRUD (active profile) ────────────────────────────────

    fun addKeyword(keyword: String) {
        if (keyword.isBlank()) return
        val idx = profiles.indexOfFirst { it.id == activeProfileId.value }
        if (idx < 0) return
        val profile = profiles[idx]
        val trimmed = keyword.trim()
        if (profile.keywords.any { it.equals(trimmed, ignoreCase = true) }) return
        profiles[idx] = profile.copy(keywords = profile.keywords + trimmed)
        saveProfiles()
    }

    fun removeKeyword(keyword: String) {
        val idx = profiles.indexOfFirst { it.id == activeProfileId.value }
        if (idx < 0) return
        val profile = profiles[idx]
        profiles[idx] = profile.copy(keywords = profile.keywords.filter { it != keyword })
        saveProfiles()
    }

    // ── Template processing ──────────────────────────────────────────

    fun processTemplate(text: String): String {
        var result = text

        // {app_name} → active profile name
        val profileName = getActiveProfile()?.name ?: "App"
        result = result.replace("{app_name}", profileName)

        // {N-M} → random number in range
        val numberPattern = Regex("\\{(\\d+)-(\\d+)\\}")
        result = numberPattern.replace(result) { match ->
            val min = match.groupValues[1].toIntOrNull() ?: 0
            val max = match.groupValues[2].toIntOrNull() ?: 0
            if (max > min) Random.nextInt(min, max + 1).toString()
            else match.value
        }

        return result
    }

    // ── Settings ─────────────────────────────────────────────────────

    fun updateSettings(settings: BotSettings) {
        botSettings.value = settings
        saveSettings()
    }

    // ── Rate limiting ────────────────────────────────────────────────

    fun recordReply() {
        replyTimestamps.add(System.currentTimeMillis())
        val oneHourAgo = System.currentTimeMillis() - 3_600_000
        replyTimestamps.removeAll { it < oneHourAgo }

        dailyReplies.value++
        prefs.edit().putInt(KEY_DAILY_COUNT, dailyReplies.value).apply()
    }

    fun getRepliesInLastHour(): Int {
        val oneHourAgo = System.currentTimeMillis() - 3_600_000
        return replyTimestamps.count { it >= oneHourAgo }
    }

    fun canSendReply(): Boolean {
        val s = botSettings.value
        if (s.maxRepliesPerHour > 0 && getRepliesInLastHour() >= s.maxRepliesPerHour) return false
        if (s.dailyReplyLimit > 0 && dailyReplies.value >= s.dailyReplyLimit) return false
        return true
    }

    fun isHourlyLimitHit(): Boolean {
        val s = botSettings.value
        return s.maxRepliesPerHour > 0 && getRepliesInLastHour() >= s.maxRepliesPerHour
    }

    fun isDailyLimitHit(): Boolean {
        val s = botSettings.value
        return s.dailyReplyLimit > 0 && dailyReplies.value >= s.dailyReplyLimit
    }

    fun getReplyDelay(): Long {
        val s = botSettings.value
        val min = (s.minDelaySeconds * 1000).toLong()
        val max = (s.maxDelaySeconds * 1000).toLong()
        return if (max > min) Random.nextLong(min, max + 1) else min
    }

    // ── Internal persistence ─────────────────────────────────────────

    private fun checkDailyReset() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString(KEY_DAILY_DATE, "") ?: ""
        if (savedDate != today) {
            dailyReplies.value = 0
            prefs.edit()
                .putString(KEY_DAILY_DATE, today)
                .putInt(KEY_DAILY_COUNT, 0)
                .apply()
        } else {
            dailyReplies.value = prefs.getInt(KEY_DAILY_COUNT, 0)
        }
    }

    private fun syncRepliesFromActiveProfile() {
        val profile = getActiveProfile()
        replies.clear()
        if (profile != null) {
            replies.addAll(profile.replies)
        }
    }

    private fun saveProfiles() {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("keywords", JSONArray(p.keywords))
                val rArr = JSONArray()
                p.replies.forEach { r ->
                    rArr.put(JSONObject().apply {
                        put("id", r.id)
                        put("text", r.text)
                        put("enabled", r.enabledForBot)
                    })
                }
                put("replies", rArr)
            })
        }
        prefs.edit()
            .putString(KEY_PROFILES, arr.toString())
            .putString(KEY_ACTIVE_PROFILE, activeProfileId.value)
            .apply()
    }

    private fun loadProfiles() {
        val json = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            profiles.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)

                val rArr = obj.getJSONArray("replies")
                val replyList = mutableListOf<ReplyItem>()
                for (j in 0 until rArr.length()) {
                    val rObj = rArr.getJSONObject(j)
                    replyList.add(
                        ReplyItem(
                            id = rObj.getString("id"),
                            text = rObj.getString("text"),
                            enabledForBot = rObj.optBoolean("enabled", true)
                        )
                    )
                }

                val kwArr = obj.optJSONArray("keywords")
                val kwList = mutableListOf<String>()
                if (kwArr != null) {
                    for (j in 0 until kwArr.length()) {
                        kwList.add(kwArr.getString(j))
                    }
                }

                profiles.add(
                    ProfileItem(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        replies = replyList,
                        keywords = kwList
                    )
                )
            }
        } catch (_: Exception) {
            profiles.clear()
        }

        activeProfileId.value = prefs.getString(KEY_ACTIVE_PROFILE, "") ?: ""
        if (activeProfileId.value.isEmpty() && profiles.isNotEmpty()) {
            activeProfileId.value = profiles.first().id
        }
    }

    private fun saveSettings() {
        val s = botSettings.value
        val obj = JSONObject().apply {
            put("minDelay", s.minDelaySeconds)
            put("maxDelay", s.maxDelaySeconds)
            put("maxPerHour", s.maxRepliesPerHour)
            put("dailyLimit", s.dailyReplyLimit)
            put("autoStopReplies", s.autoStopAfterReplies)
            put("autoStopMinutes", s.autoStopAfterMinutes)
        }
        prefs.edit().putString(KEY_SETTINGS, obj.toString()).apply()
    }

    private fun loadSettings() {
        val json = prefs.getString(KEY_SETTINGS, null) ?: return
        try {
            val obj = JSONObject(json)
            botSettings.value = BotSettings(
                minDelaySeconds = obj.optInt("minDelay", 3),
                maxDelaySeconds = obj.optInt("maxDelay", 8),
                maxRepliesPerHour = obj.optInt("maxPerHour", 0),
                dailyReplyLimit = obj.optInt("dailyLimit", 0),
                autoStopAfterReplies = obj.optInt("autoStopReplies", 0),
                autoStopAfterMinutes = obj.optInt("autoStopMinutes", 0)
            )
        } catch (_: Exception) {}
    }
}
