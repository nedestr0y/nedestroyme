package ru.nedestroy.nedestroyme

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("nedestroyme_prefs", Context.MODE_PRIVATE)

    // Главный выключатель работы защиты
    var isProtectionEnabled: Boolean
        get() = prefs.getBoolean("protection_enabled", true)
        set(value) = prefs.edit().putBoolean("protection_enabled", value).apply()

    // Режим фиксации: "PHOTO" или "VIDEO"
    var captureMode: String
        get() = prefs.getString("capture_mode", "PHOTO") ?: "PHOTO"
        set(value) = prefs.edit().putString("capture_mode", value).apply()

    // Длительность видео в секундах (от 5 до 10)
    var videoDuration: Int
        get() = prefs.getInt("video_duration", 5)
        set(value) = prefs.edit().putInt("video_duration", value.coerceIn(5, 10)).apply()

    var isGeoEnabled: Boolean
        get() = prefs.getBoolean("geo_enabled", true)
        set(value) = prefs.edit().putBoolean("geo_enabled", value).apply()

    var isWideLensEnabled: Boolean
        get() = prefs.getBoolean("wide_lens_enabled", true)
        set(value) = prefs.edit().putBoolean("wide_lens_enabled", value).apply()

    var photoCount: Int
        get() = prefs.getInt("photo_count", 1)
        set(value) = prefs.edit().putInt("photo_count", value.coerceIn(1, 5)).apply()

    var isTelegramEnabled: Boolean
        get() = prefs.getBoolean("tg_enabled", false)
        set(value) = prefs.edit().putBoolean("tg_enabled", value).apply()

    var telegramToken: String
        get() = prefs.getString("tg_token", "") ?: ""
        set(value) = prefs.edit().putString("tg_token", value.trim()).apply()

    var telegramChatId: String
        get() = prefs.getString("tg_chat_id", "") ?: ""
        set(value) = prefs.edit().putString("tg_chat_id", value.trim()).apply()
}