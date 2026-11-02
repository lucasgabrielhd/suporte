package com.faceclean.wasuporte

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("wasuporte", Context.MODE_PRIVATE)

    companion object {
        const val URL_PADRAO = "https://n8n.facecleanoficial.com.br/webhook"
        const val ROTA_IN = "wa-suporte-in"
        const val ROTA_OUT = "wa-suporte-out"
    }

    var baseUrl: String
        get() = (sp.getString("base", "") ?: "").ifBlank { URL_PADRAO }
        set(v) { sp.edit().putString("base", v.trim().trimEnd('/')).apply() }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v.trim()).apply() }

    var deviceName: String
        get() = (sp.getString("device", "") ?: "").ifBlank { "suporte" }
        set(v) { sp.edit().putString("device", v.trim()).apply() }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var groqToken: String
        get() = sp.getString("groq_token", "") ?: ""
        set(v) { sp.edit().putString("groq_token", v.trim()).apply() }

    var audioEnabled: Boolean
        get() = sp.getBoolean("audio_enabled", true)
        set(v) { sp.edit().putBoolean("audio_enabled", v).apply() }

    var sendMedia: Boolean
        get() = sp.getBoolean("send_media", true)
        set(v) { sp.edit().putBoolean("send_media", v).apply() }

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(v) { sp.edit().putBoolean("keep_awake", v).apply() }

    var fastSec: Int
        get() = sp.getInt("fast_sec", 3)
        set(v) { sp.edit().putInt("fast_sec", v.coerceIn(2, 30)).apply() }

    var slowSec: Int
        get() = sp.getInt("slow_sec", 20)
        set(v) { sp.edit().putInt("slow_sec", v.coerceIn(5, 300)).apply() }

    fun url(rota: String) = baseUrl.trimEnd('/') + "/" + rota
}
