package com.faceclean.wasuporte

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dois caminhos:
 *  ENTRADA: mensagem da cliente → POST wa-suporte-in (fila persistente, tenta até conseguir).
 *  SAÍDA:   busca no wa-suporte-out o que a atendente digitou no painel → responde pelo botão
 *           "Responder" da notificação → confirma (ack) pro n8n. Um id nunca é enviado 2x.
 */
object Engine {

    private val BACKOFF_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000)
    private const val DEBOUNCE_MS = 1_500L
    private const val ACTIVE_WINDOW_MS = 5 * 60_000L
    private const val WAKE_MS = 15 * 60_000L

    private lateinit var app: Context
    private lateinit var h: Handler
    private val pool = Executors.newFixedThreadPool(3)
    private val pollPool = Executors.newSingleThreadExecutor()

    private data class Act(val action: Notification.Action, val nome: String, val numero: String)
    private val actions = ConcurrentHashMap<String, Act>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val polling = AtomicBoolean(false)
    private val acking = AtomicBoolean(false)
    private var wl: PowerManager.WakeLock? = null

    @Volatile var lastError: String = ""
    @Volatile var lastPollAt: Long = 0L
    @Volatile var lastPollOk: Boolean = false
    @Volatile var lastSentAt: Long = 0L
    @Volatile var painelAtivo: Boolean = false
    @Volatile private var lastActivity: Long = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Store.init(app)
        val t = HandlerThread("suporte-engine").apply { start() }
        h = Handler(t.looper)
        h.post(tick)
        h.post(pollLoop)
    }

    fun setAction(conv: String, a: Notification.Action, nome: String, numero: String) { actions[conv] = Act(a, nome, numero) }

    fun actionsCount() = actions.size

    fun noteActivity() { lastActivity = System.currentTimeMillis() }

    fun kick() {
        if (!::h.isInitialized) return
        h.removeCallbacks(tick)
        h.post(tick)
    }

    fun kickPoll() {
        if (!::h.isInitialized) return
        h.removeCallbacks(pollLoop)
        h.post(pollLoop)
    }

    // ---------------- loop de entrada ----------------

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val prefs = Prefs(app)
            if (prefs.enabled) {
                try { processInbound() } catch (t: Throwable) { lastError = "motor: ${t.message}" }
                if (Store.hasAcks()) flushAcksAsync()
            }
            val busy = prefs.enabled && (Store.hasPending() || inFlight.isNotEmpty() || Store.hasAcks())
            if (prefs.enabled && (busy || prefs.keepAwake)) hold() else release()
            if (busy) h.postDelayed(this, 1000)
        }
    }

    private fun processInbound() {
        val now = System.currentTimeMillis()
        for (p in Store.pendings()) {
            val waiting = Media.ensure(app, p)
            if (p.conv in inFlight) continue
            if (now - p.lastMsgAt < DEBOUNCE_MS) continue
            if (now < p.nextAt) continue
            if (waiting) continue
            inFlight.add(p.conv)
            pool.execute {
                val err = try { postInbound(p) } catch (t: Throwable) { t.message ?: "erro" }
                h.post {
                    try {
                        if (err == null) {
                            Store.consume(p.conv, p.msgs.map { it.id }.toSet())
                            lastError = ""
                            Store.log("➡ painel recebeu ${p.msgs.size} msg de ${p.nome}")
                        } else {
                            val idx = p.attempts.coerceIn(0, BACKOFF_MS.size - 1)
                            val a = Store.retryLater(p.conv, err, BACKOFF_MS[idx])
                            lastError = err
                            Store.log("⚠ ${p.nome}: $err (tentativa $a)")
                        }
                    } finally { inFlight.remove(p.conv); kick() }
                }
            }
        }
    }

    private fun postInbound(p: Store.Pending): String? {
        val prefs = Prefs(app)
        if (prefs.token.isBlank()) return "token_nao_configurado"
        val msgs = JSONArray()
        for (m in p.msgs) {
            val o = JSONObject().put("id", m.id).put("t", m.t).put("tipo", m.tipo).put("texto", m.txt)
            if (m.transcricao.isNotBlank()) o.put("transcricao", m.transcricao)
            if (m.caption.isNotBlank()) o.put("legenda", m.caption)
            if (m.dur > 0) o.put("duracao", m.dur)
            if (m.err.isNotBlank()) o.put("erro", m.err)
            if (prefs.sendMedia && m.paths.isNotEmpty()) {
                val md = JSONArray()
                for (path in m.paths) Media.encode(path, m.tipo)?.let { md.put(it) }
                if (md.length() > 0) o.put("midias", md)
            }
            msgs.put(o)
        }
        val body = JSONObject()
            .put("tipo", "msgs")
            .put("aparelho", prefs.deviceName)
            .put("conv", p.conv)
            .put("nome", p.nome)
            .put("numero", p.numero)
            .put("numeroFonte", p.numeroFonte)
            .put("msgs", msgs)
        val (code, _) = post(prefs, Prefs.ROTA_IN, body)
        return if (code in 200..299) null else "n8n_http_$code"
    }

    // ---------------- loop de saída (painel → cliente) ----------------

    private val pollLoop: Runnable = object : Runnable {
        override fun run() {
            val prefs = Prefs(app)
            if (prefs.enabled && prefs.keepAwake) hold()
            if (prefs.enabled && prefs.token.isNotBlank() && polling.compareAndSet(false, true)) {
                pollPool.execute {
                    try { poll() } catch (t: Throwable) { lastError = "poll: ${t.message}"; lastPollOk = false } finally { polling.set(false) }
                }
            }
            val fast = painelAtivo || System.currentTimeMillis() - lastActivity < ACTIVE_WINDOW_MS
            val delay = (if (fast) prefs.fastSec else prefs.slowSec) * 1000L
            h.postDelayed(this, delay)
        }
    }

    fun poll(): String {
        val prefs = Prefs(app)
        val body = JSONObject()
            .put("aparelho", prefs.deviceName)
            .put("botoes", JSONArray(actions.keys.take(400)))
            .put("status", statusJson(prefs))
        val (code, raw) = post(prefs, Prefs.ROTA_OUT, body)
        lastPollAt = System.currentTimeMillis()
        if (code !in 200..299) {
            lastPollOk = false
            lastError = "poll: n8n_http_$code"
            return "HTTP $code"
        }
        lastPollOk = true
        val j = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        painelAtivo = j.optBoolean("rapido", false)
        val itens = j.optJSONArray("itens") ?: JSONArray()
        for (i in 0 until itens.length()) {
            val it = itens.optJSONObject(i) ?: continue
            try { sendOut(it) } catch (t: Throwable) { Store.addAck(it.optString("id"), "falhou", "app: ${t.message}") }
            if (i < itens.length() - 1) Thread.sleep(700)
        }
        if (Store.hasAcks()) flushAcks()
        return "HTTP $code · ${itens.length()} pra enviar · painel ${if (painelAtivo) "aberto" else "fechado"}"
    }

    private fun sendOut(it: JSONObject) {
        val id = it.optString("id")
        val conv = it.optString("conv")
        val texto = it.optString("texto").trim()
        if (id.isBlank()) return
        if (Store.wasSent(id)) { Store.addAck(id, "enviado", ""); return }
        var act = actions[conv]
        if (act == null) {
            val num = it.optString("numero")
            if (num.isNotBlank()) act = actions.values.firstOrNull { a -> a.numero == num }
        }
        val status = when {
            texto.isEmpty() -> "falhou"
            act == null -> "sem_notificacao"
            sendReply(act.action, texto) -> "enviado"
            else -> { actions.remove(conv); "sem_notificacao" }
        }
        if (status == "enviado") {
            Store.markSent(id)
            Store.rememberSent(conv, texto)
            Store.inc("cnt_out")
            lastSentAt = System.currentTimeMillis()
            noteActivity()
            Store.log("✅ enviado pra ${act?.nome ?: conv}: ${texto.take(40)}")
        } else {
            Store.inc("cnt_outfail")
            Store.log("❌ não enviou pra ${act?.nome ?: conv}: $status")
        }
        Store.addAck(id, status, if (status == "enviado") "" else status)
    }

    private fun sendReply(action: Notification.Action, text: String): Boolean {
        val ris = action.remoteInputs ?: return false
        return try {
            val intent = Intent()
            val b = Bundle()
            for (ri in ris) b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(ris, intent, b)
            action.actionIntent.send(app, 0, intent)
            true
        } catch (_: Exception) { false }
    }

    private fun flushAcksAsync() {
        if (acking.get()) return
        pool.execute { flushAcks() }
    }

    private fun flushAcks() {
        if (!acking.compareAndSet(false, true)) return
        try {
            val prefs = Prefs(app)
            val a = Store.acks()
            if (a.length() == 0) return
            val (code, _) = post(prefs, Prefs.ROTA_IN, JSONObject().put("tipo", "ack").put("aparelho", prefs.deviceName).put("acks", a))
            if (code in 200..299) Store.removeAcks((0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet())
        } catch (_: Throwable) {
        } finally { acking.set(false) }
    }

    // ---------------- http ----------------

    private fun post(prefs: Prefs, rota: String, body: JSONObject): Pair<Int, String> {
        val req = Request.Builder().url(prefs.url(rota))
            .header("X-WS-Token", prefs.token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            http.newCall(req).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
        } catch (e: Exception) {
            lastError = "rede: ${e.message}"
            -1 to ""
        }
    }

    fun testConnection(): String = try { poll() } catch (e: Exception) { "falhou: ${e.message}" }

    private fun statusJson(prefs: Prefs): JSONObject {
        val bm = app.getSystemService(BatteryManager::class.java)
        return JSONObject()
            .put("ativo", prefs.enabled)
            .put("acessoNotificacoes", Sys.hasNotificationAccess(app))
            .put("listenerConectado", SuporteListener.connected)
            .put("bateriaLiberada", Sys.ignoringBattery(app))
            .put("acessoArquivos", Sys.hasFilesAccess(app))
            .put("acessoContatos", Numero.temContatos(app))
            .put("audioAtivo", prefs.audioEnabled && prefs.groqToken.isNotBlank())
            .put("bateria", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("carregando", bm?.isCharging ?: false)
            .put("conversasComBotao", actions.size)
            .put("pendentes", Store.pendingCount)
            .put("recebidasHoje", Store.count("cnt_in"))
            .put("enviadasHoje", Store.count("cnt_out"))
            .put("falhasHoje", Store.count("cnt_outfail"))
            .put("ultimoErro", lastError)
            .put("versaoApp", Sys.appVersion(app))
    }

    // ---------------- wakelock ----------------

    @Synchronized
    private fun hold() {
        try {
            if (wl == null) {
                val pm = app.getSystemService(PowerManager::class.java) ?: return
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wasuporte:engine").apply { setReferenceCounted(false) }
            }
            wl?.acquire(WAKE_MS)
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun release() {
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
    }
}
