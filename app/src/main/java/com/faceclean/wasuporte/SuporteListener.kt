package com.faceclean.wasuporte

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.security.MessageDigest

/**
 * Lê as notificações do WhatsApp: guarda o botão "Responder" de cada conversa
 * (é por ele que o painel responde) e entrega pro Engine só as mensagens NOVAS
 * da cliente, ignorando as respostas que o próprio app mandou.
 */
class SuporteListener : NotificationListenerService() {

    companion object {
        private val WA_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SELF_NAMES = setOf("você", "voce", "you", "eu", "me")
        @Volatile var connected = false
            private set
        @Volatile private var instance: SuporteListener? = null

        fun rescan() { instance?.let { s -> try { s.scanActive() } catch (_: Throwable) {} } }

        fun msgId(conv: String, t: Long, txt: String): String {
            val d = MessageDigest.getInstance("SHA-1").digest("$conv|$t|$txt".toByteArray())
            return d.take(12).joinToString("") { "%02x".format(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        instance = this
        Engine.init(this)
        scanActive()
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        super.onListenerDisconnected()
        try { requestRebind(ComponentName(this, SuporteListener::class.java)) } catch (_: Throwable) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (t: Throwable) { Engine.lastError = "listener: ${t.message}" }
    }

    fun scanActive() {
        val list = try { activeNotifications } catch (_: Throwable) { null } ?: return
        for (sbn in list) try { handle(sbn) } catch (_: Throwable) {}
    }

    private fun handle(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        if (sbn.packageName !in WA_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val action = findReplyAction(n) ?: return
        val extras = n.extras ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)

        val isGroup = extras.getBoolean("android.isGroupConversation", false) || style?.isGroupConversation == true
        if (isGroup) return

        val title = (style?.conversationTitle
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        if (title.isEmpty() || title.equals("WhatsApp", ignoreCase = true) || title.equals("WhatsApp Business", ignoreCase = true)) return

        val shortcut = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.shortcutId else null
        val conv = if (!shortcut.isNullOrBlank()) shortcut else "nome:$title"
        val res = Numero.resolver(this, n, shortcut, title, conv)

        Engine.setAction(conv, action, title, res.numero)

        val seen = Store.lastSeen(conv)
        val novas = ArrayList<Store.Msg>()
        var maxT = seen

        if (style != null && style.messages.isNotEmpty()) {
            val usesPersons = style.messages.any { it.person != null }
            val userName = style.user.name?.toString()?.lowercase()
            for (m in style.messages) {
                val t = m.timestamp
                if (t > maxT) maxT = t
                if (t <= seen) continue
                val txt = m.text?.toString()?.trim().orEmpty()
                if (txt.isEmpty()) continue
                val sender = m.person?.name?.toString()?.trim()?.lowercase()
                val isSelf = (usesPersons && m.person == null) || (sender != null && (sender in SELF_NAMES || sender == userName))
                if (isSelf) continue
                if (Store.isOwnReply(conv, txt)) continue
                novas.add(Media.toMsg(this, msgId(conv, t, txt), t, txt, prefs))
            }
        } else {
            val txt = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
            val t = sbn.postTime
            if (t > maxT) maxT = t
            if (t > seen && txt.isNotEmpty() && !Store.isOwnReply(conv, txt)) novas.add(Media.toMsg(this, msgId(conv, t, txt), t, txt, prefs))
        }

        if (maxT > seen) Store.setSeen(conv, maxT)
        if (novas.isEmpty()) return

        Store.addMessages(conv, title, res.numero, res.fonte, false, novas)
        Store.inc("cnt_in")
        Store.log("📩 $title${if (res.numero.isNotBlank()) " (${res.numero})" else ""}: ${novas.last().txt.take(40)}")
        Engine.noteActivity()
        Engine.kick()
    }

    private fun findReplyAction(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        actions.firstOrNull { a -> a.remoteInputs?.any { it.resultKey.contains("reply", ignoreCase = true) } == true }?.let { return it }
        return actions.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
    }
}
