package com.faceclean.wasuporte

import android.Manifest
import android.app.Notification
import android.app.Person
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat

/**
 * Descobre o número do cliente a partir da notificação do WhatsApp, tentando
 * várias fontes em ordem. Devolve o número só com dígitos (55DDD9XXXXXXXX)
 * e a fonte usada, pra ficar rastreável no n8n.
 *
 * Fontes, em ordem:
 *  1. shortcutId "5588...@s.whatsapp.net"
 *  2. Person da MessagingStyle / EXTRA_PEOPLE_LIST com uri "tel:+55..."
 *  3. Título sem letras (contato não salvo: "+55 88 9999-9999")
 *  4. Agenda do celular (precisa de READ_CONTATOS) — nome exato da conversa
 *  5. Cache: número já descoberto antes pra essa mesma conversa
 */
object Numero {

    data class R(val numero: String, val fonte: String)

    fun resolver(ctx: Context, n: Notification, shortcut: String?, title: String, conv: String): R {
        val extras = n.extras ?: Bundle()
        val style = try { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) } catch (_: Throwable) { null }

        shortcut?.let { s ->
            if (s.contains("@s.whatsapp.net")) {
                val d = norm(s.substringBefore("@"))
                if (d.isNotBlank()) return lembrar(conv, R(d, "shortcut"))
            }
        }

        style?.messages?.forEach { m ->
            val p = m.person ?: return@forEach
            fromPerson(p.uri, p.key)?.let { return lembrar(conv, R(it, "person")) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                @Suppress("DEPRECATION")
                val list = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
                list?.forEach { p -> fromPerson(p.uri, p.key)?.let { return lembrar(conv, R(it, "people_list")) } }
            } catch (_: Throwable) {}
        }
        try {
            @Suppress("DEPRECATION")
            extras.getStringArray(Notification.EXTRA_PEOPLE)?.forEach { u -> fromPerson(u, null)?.let { return lembrar(conv, R(it, "people")) } }
        } catch (_: Throwable) {}

        if (title.isNotBlank() && title.none { it.isLetter() }) {
            val d = norm(title)
            if (d.isNotBlank()) return lembrar(conv, R(d, "titulo"))
        }

        if (title.isNotBlank() && temContatos(ctx)) {
            daAgenda(ctx, title)?.let { return lembrar(conv, R(it, "agenda")) }
        }

        val cache = Store.numeroCache(conv)
        if (cache.isNotBlank()) return R(cache, "cache")
        val motivo = when {
            !temContatos(ctx) -> "sem_permissao_contatos"
            title.isBlank() -> "sem_titulo"
            else -> "nao_achou_na_agenda"
        }
        return R("", motivo)
    }

    fun temContatos(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun lembrar(conv: String, r: R): R { if (r.numero.isNotBlank()) Store.setNumeroCache(conv, r.numero); return r }

    private fun fromPerson(uri: String?, key: String?): String? {
        for (c in listOf(uri, key)) {
            if (c.isNullOrBlank()) continue
            if (c.startsWith("tel:", ignoreCase = true)) { val d = norm(c.substring(4)); if (d.isNotBlank()) return d }
            if (c.contains("@s.whatsapp.net")) { val d = norm(c.substringBefore("@")); if (d.isNotBlank()) return d }
        }
        return null
    }

    /**
     * Agenda: tenta o nome exato, depois sem o sufixo entre parênteses
     * ("Lucas Portela (Pessoal)" → "Lucas Portela"), depois por prefixo.
     * Em cada tentativa aceita o primeiro telefone celular válido do contato.
     */
    private fun daAgenda(ctx: Context, nome: String): String? {
        val limpo = nome.trim()
        val semSufixo = limpo.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]\\s*$"), "").trim()
        val tentativas = linkedSetOf(limpo)
        if (semSufixo.isNotBlank() && semSufixo != limpo) tentativas.add(semSufixo)
        for (t in tentativas) {
            consultar(ctx, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ? COLLATE NOCASE", arrayOf(t))?.let { return it }
        }
        for (t in tentativas) {
            if (t.length < 4) continue
            consultar(ctx, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? COLLATE NOCASE", arrayOf("$t%"))?.let { return it }
        }
        return null
    }

    private fun consultar(ctx: Context, where: String, args: Array<String>): String? = try {
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE)
        var achado: String? = null
        var reserva: String? = null
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, where, args, null)?.use { c ->
            while (c.moveToNext() && achado == null) {
                val d = norm(c.getString(1) ?: "").ifBlank { norm(c.getString(0) ?: "") }
                if (d.isBlank()) continue
                val celular = d.length == 13 && d[4] == '9'
                if (celular) achado = d else if (reserva == null) reserva = d
            }
        }
        achado ?: reserva
    } catch (_: Throwable) { null }

    /** Só dígitos, com DDI 55 na frente quando vier no formato nacional (10–11 dígitos). */
    fun norm(s: String): String {
        var d = s.filter { it.isDigit() }
        if (d.startsWith("0") && d.length in 11..12) d = d.substring(1)
        if (d.length in 10..11) d = "55$d"
        return if (d.length in 12..15) d else ""
    }
}
