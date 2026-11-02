package com.faceclean.wasuporte

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Só age quando o EnviaFoto "arma" um envio: acha o botão Enviar da tela de
 * prévia da foto do WhatsApp e toca nele uma única vez. Fora disso, não faz nada.
 */
class SuporteAcessibilidade : AccessibilityService() {

    companion object {
        @Volatile var instance: SuporteAcessibilidade? = null
            private set

        @Volatile private var armado = false
        @Volatile private var clicou = false
        @Volatile private var pacote = "com.whatsapp.w4b"
        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var seq = 0

        fun ligado() = instance != null

        @Synchronized
        fun armar(pkg: String): Int {
            latch?.countDown()
            seq += 1
            pacote = pkg
            clicou = false
            armado = true
            latch = CountDownLatch(1)
            instance?.varrer(seq)
            return seq
        }

        /** true = tocou em Enviar. */
        fun esperar(timeoutMs: Long): Boolean {
            val l = latch ?: return false
            try { l.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
            armado = false
            return clicou
        }

        fun desarmar() { armado = false; latch?.countDown() }

        private fun marcarClique() { clicou = true; armado = false; latch?.countDown() }
    }

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var ultimo = 0L
    private val rotulos = setOf("enviar", "send")

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun bloquear(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armado) return
        if (event?.packageName?.toString() != pacote) return
        if (SystemClock.uptimeMillis() - ultimo < 300) return
        ui.post { tentar() }
    }

    fun varrer(s: Int) {
        ui.post(object : Runnable {
            override fun run() {
                if (s != seq || !armado) return
                try { tentar() } catch (_: Throwable) {}
                if (s == seq && armado) ui.postDelayed(this, 700)
            }
        })
    }

    private fun tentar() {
        if (!armado) return
        ultimo = SystemClock.uptimeMillis()
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return
        if (root.packageName?.toString() != pacote) return
        val btn = botaoEnviar(root) ?: return
        synchronized(Companion) {
            if (!armado) return
            armado = false
        }
        if (clicar(btn)) marcarClique() else { clicou = false; latch?.countDown() }
    }

    private fun botaoEnviar(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in listOf("$pacote:id/send", "$pacote:id/send_btn", "$pacote:id/send_button")) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser && it.isEnabled }?.let { return it }
        }
        return porDescricao(root, 0)
    }

    private fun porDescricao(n: AccessibilityNodeInfo?, d: Int): AccessibilityNodeInfo? {
        if (n == null || d > 40) return null
        val desc = n.contentDescription?.toString()?.trim()?.lowercase()
        if (desc != null && desc in rotulos && n.isVisibleToUser && n.isEnabled) return n
        for (i in 0 until n.childCount) porDescricao(n.getChild(i), d + 1)?.let { return it }
        return null
    }

    private fun clicar(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var d = 0
        while (n != null && d < 6) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            d++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
