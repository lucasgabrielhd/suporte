package com.faceclean.wasuporte

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Áudio e foto da cliente.
 *  - Áudio: acha o .opus que o WhatsApp baixou, transcreve na Groq (Whisper) e guarda o arquivo pra ir pro painel.
 *  - Foto: acha o .jpg baixado e guarda pra ir pro painel (reduzida a 1280px).
 * Se não achar o arquivo, a mensagem segue só com o aviso ("🎤 Mensagem de voz", "📷 Foto") — nunca trava.
 */
object Media {

    private const val GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
    private const val MODEL = "whisper-large-v3-turbo"

    private val WAITS_MS = longArrayOf(1200, 800, 800, 2000, 4000, 8000)
    private const val WINDOW_BEFORE_MS = 30_000L
    private const val WINDOW_AFTER_MS = 90_000L
    private const val MAX_AUDIO_TRANSCRIBE = 24L * 1024 * 1024
    private const val MAX_AUDIO_UPLOAD = 3L * 1024 * 1024
    private const val IMG_SIDE = 1280
    private const val MAX_IMAGES = 4
    private val AUDIO_EXTS = setOf("opus", "ogg", "m4a", "mp3", "aac", "amr", "wav", "mp4", "webm")
    private val IMG_EXTS = setOf("jpg", "jpeg", "png", "webp")

    private const val W4B_MEDIA = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media"
    private const val WA_MEDIA = "Android/media/com.whatsapp/WhatsApp/Media"
    private const val W4B_LEGACY = "WhatsApp Business/Media"
    private const val WA_LEGACY = "WhatsApp/Media"

    private val VOICE_WORDS = Regex(
        """^[\p{So}\p{Sk}️‍\s]*(mensagem de voz|mensagem de áudio|mensagem de audio|nota de voz|áudio|audio|voice message)\s*(\(\s*\d{1,2}:\d{2}\s*\))?\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val MIC = Regex("""^\s*(🎤|🎙|🎧|🎵)""")
    private val DURATION = Regex("""\(?\s*(\d{1,2}):(\d{2})\s*\)?\s*$""")
    private val PHOTO_PREFIX = Regex("""^\s*(📷|📸|🖼)[️\s]*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val PLAIN = Regex("""^\s*(foto|imagem|photo|image)\s*$""", RegexOption.IGNORE_CASE)
    private val MULTI = Regex("""^\s*(\d{1,2})\s*(fotos|imagens|photos|images)\s*$""", RegexOption.IGNORE_CASE)

    private val pool = Executors.newFixedThreadPool(2)
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val pickLock = Any()

    @Volatile var lastAudio: String = ""
    @Volatile var lastImage: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    // ---------------- detecção ----------------

    private fun isVoice(txt: String): Boolean {
        val t = txt.trim()
        if (t.isEmpty() || t.length > 60) return false
        if (VOICE_WORDS.matches(t)) return true
        return MIC.containsMatchIn(t) && (DURATION.containsMatchIn(t) || t.length <= 30)
    }

    private fun durationFromText(txt: String): Int {
        val m = DURATION.find(txt.trim()) ?: return -1
        val min = m.groupValues[1].toIntOrNull() ?: return -1
        val sec = m.groupValues[2].toIntOrNull() ?: return -1
        return min * 60 + sec
    }

    private fun parsePhoto(txt: String): Pair<String, Int>? {
        val t = txt.trim()
        if (t.isEmpty()) return null
        if (PLAIN.matches(t)) return "" to 1
        MULTI.find(t)?.let { return "" to (it.groupValues[1].toIntOrNull() ?: 1) }
        val m = PHOTO_PREFIX.find(t) ?: return null
        val rest = m.groupValues[2].trim()
        if (rest.isEmpty() || PLAIN.matches(rest)) return "" to 1
        MULTI.find(rest)?.let { return "" to (it.groupValues[1].toIntOrNull() ?: 1) }
        return rest to 1
    }

    fun toMsg(ctx: Context, id: String, t: Long, txt: String, prefs: Prefs): Store.Msg {
        val files = Sys.hasFilesAccess(ctx)
        if (isVoice(txt)) {
            val want = prefs.sendMedia || (prefs.audioEnabled && prefs.groqToken.isNotBlank())
            return Store.Msg(id, t, txt, tipo = "audio", st = if (want && files) Store.WAIT else Store.OFF,
                seenAt = System.currentTimeMillis(), dur = durationFromText(txt))
        }
        parsePhoto(txt)?.let { (cap, n) ->
            return Store.Msg(id, t, txt, tipo = "imagem", st = if (prefs.sendMedia && files) Store.WAIT else Store.OFF,
                seenAt = System.currentTimeMillis(), caption = cap, n = n.coerceIn(1, MAX_IMAGES))
        }
        return Store.Msg(id, t, txt)
    }

    // ---------------- processamento ----------------

    /** Dispara o processamento das mídias aguardando. true = ainda tem mídia aguardando nessa conversa. */
    fun ensure(ctx: Context, p: Store.Pending): Boolean {
        var waiting = false
        val app = ctx.applicationContext
        for (m in p.msgs) {
            if (m.st != Store.WAIT) continue
            waiting = true
            val key = "${p.conv}|${m.id}"
            if (!running.add(key)) continue
            pool.execute {
                try {
                    if (m.tipo == "audio") workAudio(app, p, m, key) else workImage(app, p, m, key)
                } catch (t: Throwable) {
                    Store.updateMedia(p.conv, m.id, Store.FAIL, "", emptyList(), "erro: ${t.message}")
                } finally {
                    running.remove(key)
                    Engine.kick()
                }
            }
        }
        return waiting
    }

    private fun waitFor(base: Long, find: () -> Boolean) {
        var at = 0L
        for (w in WAITS_MS) {
            at += w
            val sleep = base + at - System.currentTimeMillis()
            if (sleep > 0) Thread.sleep(sleep)
            if (find()) return
        }
    }

    private fun workAudio(app: Context, p: Store.Pending, m: Store.Msg, key: String) {
        val prefs = Prefs(app)
        val base = if (m.seenAt > 0) m.seenAt else System.currentTimeMillis()
        var file: File? = null
        waitFor(base) { file = pickAudio(base, m.dur, key); file != null }
        val f = file
        if (f == null) {
            Store.updateMedia(p.conv, m.id, Store.FAIL, "", emptyList(), "audio_nao_encontrado")
            lastAudio = "audio_nao_encontrado"
            Store.log("⚠ áudio de ${p.nome}: arquivo não encontrado")
            return
        }
        var tr = ""
        var err = ""
        if (prefs.audioEnabled && prefs.groqToken.isNotBlank()) {
            if (f.length() > MAX_AUDIO_TRANSCRIBE) err = "audio_grande_demais"
            else {
                val r = transcribe(f.readBytes(), f.name, prefs.groqToken)
                tr = r.first.orEmpty()
                err = r.second
            }
        }
        val paths = if (prefs.sendMedia && f.length() <= MAX_AUDIO_UPLOAD) listOf(f.absolutePath) else emptyList()
        Store.updateMedia(p.conv, m.id, Store.OK, tr, paths, err)
        lastAudio = if (tr.isNotBlank()) "ok" else err.ifBlank { "sem_transcricao" }
        Store.log(if (tr.isNotBlank()) "🎤 ${p.nome}: \"${tr.take(60)}\"" else "🎤 ${p.nome}: áudio sem transcrição ${if (err.isNotBlank()) "($err)" else ""}")
    }

    private fun workImage(app: Context, p: Store.Pending, m: Store.Msg, key: String) {
        val base = if (m.seenAt > 0) m.seenAt else System.currentTimeMillis()
        var files: List<File> = emptyList()
        waitFor(base) { files = pickImages(base, m.n, key); files.isNotEmpty() }
        if (files.isEmpty()) {
            Store.updateMedia(p.conv, m.id, Store.FAIL, "", emptyList(), "imagem_nao_encontrada")
            lastImage = "imagem_nao_encontrada"
            Store.log("⚠ foto de ${p.nome}: arquivo não encontrado (download automático ligado?)")
            return
        }
        Store.updateMedia(p.conv, m.id, Store.OK, "", files.map { it.absolutePath }, "")
        lastImage = "ok"
        Store.log("📷 ${p.nome}: ${files.size} foto(s) pronta(s)")
    }

    // ---------------- arquivos ----------------

    private fun audioCandidates(): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        val voiceRoots = listOf(
            File(sd, "$W4B_MEDIA/WhatsApp Business Voice Notes"),
            File(sd, "$WA_MEDIA/WhatsApp Voice Notes"),
            File(sd, "$W4B_LEGACY/WhatsApp Business Voice Notes"),
            File(sd, "$WA_LEGACY/WhatsApp Voice Notes")
        )
        for (root in voiceRoots) {
            val weeks = root.listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
                ?.sortedByDescending { it.name }?.take(2) ?: continue
            for (w in weeks) w.listFiles()?.filterTo(out) { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
        }
        val audioRoots = listOf(
            File(sd, "$W4B_MEDIA/WhatsApp Business Audio"),
            File(sd, "$WA_MEDIA/WhatsApp Audio"),
            File(sd, "$W4B_LEGACY/WhatsApp Business Audio"),
            File(sd, "$WA_LEGACY/WhatsApp Audio")
        )
        for (d in audioRoots) d.listFiles()?.filterTo(out) { it.isFile && it.extension.lowercase() in AUDIO_EXTS }
        return out
    }

    private fun imageCandidates(): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        val dirs = listOf(
            File(sd, "$W4B_MEDIA/WhatsApp Business Images"),
            File(sd, "$WA_MEDIA/WhatsApp Images"),
            File(sd, "$W4B_LEGACY/WhatsApp Business Images"),
            File(sd, "$WA_LEGACY/WhatsApp Images")
        )
        val out = ArrayList<File>()
        for (d in dirs) d.listFiles()?.filterTo(out) { it.isFile && it.extension.lowercase() in IMG_EXTS }
        return out
    }

    private fun fileDurationSec(f: File): Int = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            if (ms < 0) -1 else ((ms + 500) / 1000).toInt()
        } finally { try { r.release() } catch (_: Throwable) {} }
    } catch (_: Throwable) { -1 }

    private fun pickAudio(base: Long, dur: Int, key: String): File? = synchronized(pickLock) {
        val lo = base - WINDOW_BEFORE_MS
        val hi = base + WINDOW_AFTER_MS
        val cands = audioCandidates().filter { f -> f.lastModified() in lo..hi && f.length() > 0 && Store.mediaFileFree(f.absolutePath, key) }
        if (cands.isEmpty()) return@synchronized null
        val ok = if (dur > 0) cands.filter { val d = fileDurationSec(it); d < 0 || abs(d - dur) <= 2 } else cands
        val best = ok.minByOrNull { abs(it.lastModified() - base) } ?: return@synchronized null
        val len = best.length()
        Thread.sleep(300)
        if (best.length() != len) return@synchronized null
        Store.claimMediaFile(best.absolutePath, key)
        best
    }

    private fun pickImages(base: Long, n: Int, key: String): List<File> = synchronized(pickLock) {
        val lo = base - 20_000L
        val hi = base + WINDOW_AFTER_MS
        val cands = imageCandidates().filter { f -> f.lastModified() in lo..hi && f.length() > 0 && Store.mediaFileFree(f.absolutePath, key) }
            .sortedBy { abs(it.lastModified() - base) }.take(n)
        if (cands.isEmpty()) return@synchronized emptyList()
        val sizes = cands.map { it.length() }
        Thread.sleep(300)
        if (cands.map { it.length() } != sizes) return@synchronized emptyList()
        for (f in cands) Store.claimMediaFile(f.absolutePath, key)
        cands
    }

    /** Arquivo → {mime, b64} pro painel. Foto vai reduzida (1280px, JPEG 75). null se o arquivo sumiu. */
    fun encode(path: String, tipo: String): JSONObject? = try {
        val f = File(path)
        if (!f.isFile) null
        else if (tipo == "audio") {
            if (f.length() > MAX_AUDIO_UPLOAD) null
            else JSONObject().put("mime", audioMime(f.extension)).put("b64", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        } else {
            val raw = f.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMG_SIDE) sample *= 2
            val bmp0 = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp0 == null) null else {
                val side = max(bmp0.width, bmp0.height)
                val bmp = if (side > IMG_SIDE) {
                    val k = IMG_SIDE.toFloat() / side
                    Bitmap.createScaledBitmap(bmp0, (bmp0.width * k).toInt(), (bmp0.height * k).toInt(), true).also { if (it != bmp0) bmp0.recycle() }
                } else bmp0
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                bmp.recycle()
                JSONObject().put("mime", "image/jpeg").put("b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        }
    } catch (_: Throwable) { null }

    private fun audioMime(ext: String) = when (ext.lowercase()) {
        "opus", "ogg" -> "audio/ogg"
        "m4a", "mp4" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "aac" -> "audio/aac"
        "amr" -> "audio/amr"
        else -> "audio/ogg"
    }

    // ---------------- Groq ----------------

    private fun transcribe(bytes: ByteArray, name: String, token: String): Pair<String?, String> {
        val ext = name.substringAfterLast('.', "").lowercase()
        val upName = when (ext) { "m4a", "mp4" -> "audio.m4a"; "mp3" -> "audio.mp3"; "wav" -> "audio.wav"; "webm" -> "audio.webm"; "aac" -> "audio.aac"; "amr" -> "audio.amr"; else -> "audio.ogg" }
        var lastErr = "groq_falhou"
        for (i in 0 until 3) {
            if (i == 1) Thread.sleep(2000)
            if (i == 2) Thread.sleep(5000)
            try {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("temperature", "0")
                    .addFormDataPart("response_format", "json")
                    .addFormDataPart("language", "pt")
                    .addFormDataPart("file", upName, bytes.toRequestBody(audioMime(ext).toMediaType()))
                    .build()
                val req = Request.Builder().url(GROQ_URL).header("Authorization", "Bearer $token").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val txt = (try { JSONObject(raw).optString("text") } catch (_: Exception) { "" }).trim()
                        return if (txt.isNotBlank()) txt to "" else null to "transcricao_vazia"
                    }
                    lastErr = "groq_http_${resp.code}"
                    if (resp.code in setOf(400, 401, 403, 404, 413, 415)) return null to lastErr
                }
            } catch (e: IOException) {
                lastErr = "groq_rede: ${e.message}"
            }
        }
        return null to lastErr
    }

    fun testGroq(token: String): String {
        if (token.isBlank()) return "❌ Cole o token da Groq (gsk_...)"
        return try {
            val req = Request.Builder().url(GROQ_MODELS_URL).header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> "✅ Groq conectada"
                    resp.code == 401 || resp.code == 403 -> "❌ Token recusado (HTTP ${resp.code})"
                    else -> "⚠ Groq respondeu HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) { "❌ Sem conexão com a Groq: ${e.message}" }
    }
}
