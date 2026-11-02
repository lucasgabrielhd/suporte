package com.faceclean.wasuporte

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Estado persistente (sobrevive a reinício do app/celular):
 *  - pending: mensagens de cliente ainda NÃO entregues ao n8n (fila por conversa)
 *  - acks:    confirmações de envio ainda não entregues ao n8n
 *  - done:    ids do painel já enviados ao cliente (nunca manda 2x)
 *  - seen:    último timestamp de mensagem já lido por conversa
 *  - sent:    textos que o próprio app mandou (pra não voltar como mensagem da cliente)
 */
object Store {

    const val WAIT = 0
    const val OK = 1
    const val FAIL = 2
    const val OFF = 3

    private const val SEEN_MAX = 1500
    private const val DONE_MAX = 800
    private const val LOG_MAX = 120
    private const val MEDIA_USED_MAX = 400
    private val lock = Any()
    private lateinit var ctx: Context

    fun init(c: Context) { if (!::ctx.isInitialized) ctx = c.applicationContext }

    private val sp get() = ctx.getSharedPreferences("wasuporte_store", Context.MODE_PRIVATE)

    data class Msg(
        val id: String,
        val t: Long,
        val txt: String,
        val tipo: String = "texto",
        val st: Int = OK,
        val seenAt: Long = 0L,
        val dur: Int = -1,
        val caption: String = "",
        val n: Int = 1,
        val transcricao: String = "",
        val paths: List<String> = emptyList(),
        val err: String = ""
    )

    data class Pending(
        val conv: String,
        val nome: String,
        val numero: String,
        val numeroFonte: String,
        val grupo: Boolean,
        val msgs: List<Msg>,
        val lastMsgAt: Long,
        val attempts: Int,
        val nextAt: Long,
        val lastErr: String
    )

    private fun obj(key: String): JSONObject = try { JSONObject(sp.getString(key, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun arr(key: String): JSONArray = try { JSONArray(sp.getString(key, "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun save(key: String, v: Any) { sp.edit().putString(key, v.toString()).commit() }

    private fun msgToJson(m: Msg) = JSONObject()
        .put("id", m.id).put("t", m.t).put("txt", m.txt).put("tipo", m.tipo).put("st", m.st)
        .put("sa", m.seenAt).put("d", m.dur).put("c", m.caption).put("n", m.n)
        .put("tr", m.transcricao).put("p", JSONArray(m.paths)).put("e", m.err)

    private fun msgFromJson(o: JSONObject): Msg {
        val p = o.optJSONArray("p") ?: JSONArray()
        return Msg(
            o.optString("id"), o.optLong("t"), o.optString("txt"), o.optString("tipo", "texto"), o.optInt("st", OK),
            o.optLong("sa"), o.optInt("d", -1), o.optString("c"), o.optInt("n", 1),
            o.optString("tr"), (0 until p.length()).map { p.optString(it) }, o.optString("e")
        )
    }

    private fun toPending(o: JSONObject): Pending {
        val a = o.optJSONArray("msgs") ?: JSONArray()
        return Pending(
            o.optString("conv"), o.optString("nome"), o.optString("numero"), o.optString("numeroFonte"),
            o.optBoolean("grupo"), (0 until a.length()).map { msgFromJson(a.getJSONObject(it)) },
            o.optLong("lastMsgAt"), o.optInt("attempts"), o.optLong("nextAt"), o.optString("lastErr")
        )
    }

    // ---------------- fila de entrada ----------------

    fun addMessages(conv: String, nome: String, numero: String, numeroFonte: String, grupo: Boolean, novas: List<Msg>) = synchronized(lock) {
        if (novas.isEmpty()) return@synchronized
        val all = obj("pending")
        val o = all.optJSONObject(conv) ?: JSONObject().put("conv", conv).put("attempts", 0).put("nextAt", 0L).put("msgs", JSONArray())
        o.put("nome", nome).put("grupo", grupo).put("lastMsgAt", System.currentTimeMillis())
        if (numero.isNotBlank() || o.optString("numero").isBlank()) o.put("numero", numero).put("numeroFonte", numeroFonte)
        val a = o.getJSONArray("msgs")
        val ids = (0 until a.length()).map { a.getJSONObject(it).optString("id") }.toSet()
        for (m in novas) if (m.id !in ids) a.put(msgToJson(m))
        o.put("attempts", 0).put("nextAt", 0L)
        all.put(conv, o)
        save("pending", all)
    }

    fun pendings(): List<Pending> = synchronized(lock) {
        val all = obj("pending")
        all.keys().asSequence().mapNotNull { all.optJSONObject(it)?.let(::toPending) }.toList()
    }

    fun hasPending(): Boolean = synchronized(lock) { obj("pending").length() > 0 }

    val pendingCount: Int get() = synchronized(lock) { obj("pending").length() }

    fun consume(conv: String, ids: Set<String>) = synchronized(lock) {
        val all = obj("pending")
        val o = all.optJSONObject(conv) ?: return@synchronized
        val a = o.getJSONArray("msgs")
        val rest = JSONArray()
        for (i in 0 until a.length()) { val m = a.getJSONObject(i); if (m.optString("id") !in ids) rest.put(m) }
        if (rest.length() == 0) all.remove(conv) else { o.put("msgs", rest).put("attempts", 0).put("nextAt", 0L).put("lastErr", ""); all.put(conv, o) }
        save("pending", all)
    }

    fun retryLater(conv: String, err: String, delayMs: Long): Int = synchronized(lock) {
        val all = obj("pending")
        val o = all.optJSONObject(conv) ?: return@synchronized 0
        val n = o.optInt("attempts") + 1
        o.put("attempts", n).put("nextAt", System.currentTimeMillis() + delayMs).put("lastErr", err)
        all.put(conv, o)
        save("pending", all)
        n
    }

    fun updateMedia(conv: String, id: String, st: Int, transcricao: String, paths: List<String>, err: String) = synchronized(lock) {
        val all = obj("pending")
        val o = all.optJSONObject(conv) ?: return@synchronized
        val a = o.optJSONArray("msgs") ?: return@synchronized
        var hit = false
        for (i in 0 until a.length()) {
            val m = a.optJSONObject(i) ?: continue
            if (m.optString("id") == id && m.optInt("st", OK) == WAIT) {
                m.put("st", st).put("tr", transcricao).put("p", JSONArray(paths)).put("e", err)
                hit = true
            }
        }
        if (hit) { all.put(conv, o); save("pending", all) }
    }

    // ---------------- arquivos de mídia já usados ----------------

    fun mediaFileFree(path: String, key: String): Boolean = synchronized(lock) {
        val k = obj("media_used").optJSONObject(path) ?: return@synchronized true
        k.optString("k") == key
    }

    fun claimMediaFile(path: String, key: String) = synchronized(lock) {
        val o = obj("media_used")
        o.put(path, JSONObject().put("k", key).put("t", System.currentTimeMillis()))
        if (o.length() > MEDIA_USED_MAX) {
            val keys = o.keys().asSequence().toList().sortedBy { o.optJSONObject(it)?.optLong("t") ?: 0L }
            keys.take(o.length() - MEDIA_USED_MAX).forEach { o.remove(it) }
        }
        save("media_used", o)
    }

    // ---------------- envios do painel ----------------

    fun wasSent(id: String): Boolean = synchronized(lock) { obj("done").has(id) }

    fun markSent(id: String) = synchronized(lock) {
        val o = obj("done")
        o.put(id, System.currentTimeMillis())
        if (o.length() > DONE_MAX) {
            val keys = o.keys().asSequence().toList().sortedBy { o.optLong(it) }
            keys.take(o.length() - DONE_MAX).forEach { o.remove(it) }
        }
        save("done", o)
    }

    fun addAck(id: String, status: String, erro: String) = synchronized(lock) {
        val a = arr("acks")
        val keep = JSONArray()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("id") != id) keep.put(x) }
        keep.put(JSONObject().put("id", id).put("status", status).put("erro", erro).put("em", System.currentTimeMillis()))
        save("acks", keep)
    }

    fun acks(): JSONArray = synchronized(lock) { arr("acks") }

    fun hasAcks(): Boolean = synchronized(lock) { arr("acks").length() > 0 }

    fun removeAcks(ids: Set<String>) = synchronized(lock) {
        val a = arr("acks")
        val keep = JSONArray()
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); if (x.optString("id") !in ids) keep.put(x) }
        save("acks", keep)
    }

    // ---------------- seen ----------------

    fun lastSeen(conv: String): Long = synchronized(lock) { obj("seen").optLong(conv, 0L) }

    fun setSeen(conv: String, t: Long) = synchronized(lock) {
        val o = obj("seen")
        if (t <= o.optLong(conv, 0L)) return@synchronized
        o.put(conv, t)
        if (o.length() > SEEN_MAX) {
            val keys = o.keys().asSequence().toList().sortedBy { o.optLong(it) }
            keys.take(o.length() - SEEN_MAX).forEach { o.remove(it) }
        }
        save("seen", o)
    }

    // ---------------- anti-eco ----------------

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }.take(80)

    fun rememberSent(conv: String, txt: String) = synchronized(lock) {
        val a = arr("sent")
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue; if (now - o.optLong("t") < 60 * 60_000L) keep.put(o) }
        keep.put(JSONObject().put("c", conv).put("k", norm(txt)).put("t", now))
        save("sent", keep)
    }

    fun isOwnReply(conv: String, txt: String): Boolean = synchronized(lock) {
        val k = norm(txt)
        if (k.isEmpty()) return@synchronized false
        val a = arr("sent")
        val now = System.currentTimeMillis()
        (0 until a.length()).any {
            val o = a.optJSONObject(it)
            o != null && o.optString("c") == conv && o.optString("k") == k && now - o.optLong("t") < 60 * 60_000L
        }
    }

    // ---------------- contadores e log ----------------

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun inc(key: String) = synchronized(lock) {
        val d = today()
        if (sp.getString("cnt_day", "") != d) {
            sp.edit().putString("cnt_day", d).putInt("cnt_in", 0).putInt("cnt_out", 0).putInt("cnt_outfail", 0).commit()
        }
        sp.edit().putInt(key, sp.getInt(key, 0) + 1).commit()
    }

    fun count(key: String): Int = synchronized(lock) { if (sp.getString("cnt_day", "") != today()) 0 else sp.getInt(key, 0) }

    fun log(msg: String) = synchronized(lock) {
        val a = arr("log")
        val line = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        val out = JSONArray()
        for (i in maxOf(0, a.length() - (LOG_MAX - 1)) until a.length()) out.put(a.get(i))
        out.put(line)
        sp.edit().putString("log", out.toString()).apply()
    }

    fun logLines(): List<String> = synchronized(lock) { val a = arr("log"); (0 until a.length()).map { a.optString(it) }.reversed() }

    // ---------------- número por conversa ----------------

    fun numeroCache(conv: String): String = synchronized(lock) { obj("numeros").optString(conv, "") }

    fun setNumeroCache(conv: String, numero: String) = synchronized(lock) {
        if (numero.isBlank()) return@synchronized
        val o = obj("numeros")
        if (o.optString(conv) == numero) return@synchronized
        o.put(conv, numero)
        if (o.length() > 3000) { val k = o.keys(); if (k.hasNext()) o.remove(k.next()) }
        sp.edit().putString("numeros", o.toString()).apply()
    }
}
