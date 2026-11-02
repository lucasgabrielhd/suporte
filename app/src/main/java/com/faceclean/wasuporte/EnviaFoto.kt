package com.faceclean.wasuporte

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.view.WindowManager
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Foto do painel → cliente. O botão "Responder" da notificação só aceita texto,
 * então aqui o app acende a tela, abre a prévia da foto direto no chat da cliente
 * (Intent com "jid") e a acessibilidade toca em Enviar. Depois volta pra home,
 * senão o WhatsApp para de gerar notificação e o painel fica cego.
 */
object EnviaFoto {

    private val lock = Any()

    fun enviar(ctx: Context, numero: String, mime: String, b64: String, legenda: String): Pair<String, String> {
        synchronized(lock) { return enviarAgora(ctx, numero, mime, b64, legenda) }
    }

    private fun enviarAgora(ctx: Context, numero: String, mime: String, b64: String, legenda: String): Pair<String, String> {
        val acc = SuporteAcessibilidade.instance ?: return "falhou" to "acessibilidade_desligada"
        val dig = numero.filter { it.isDigit() }
        if (dig.length < 12) return "falhou" to "sem_numero"
        val pkg = pacoteWhatsApp(ctx) ?: return "falhou" to "whatsapp_nao_instalado"

        val arq = try {
            val dir = File(ctx.cacheDir, "fotos").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
            File(dir, "painel_${System.currentTimeMillis()}.${if (mime.contains("png")) "png" else "jpg"}").apply { writeBytes(Base64.decode(b64, Base64.DEFAULT)) }
        } catch (e: Exception) { return "falhou" to "foto_invalida" }

        val telaEstavaApagada = !Tela.ligada(ctx)
        if (!Tela.acordar(ctx)) { Tela.soltar(); return "falhou" to "tela_bloqueada_com_senha" }

        try {
            acc.home()
            Thread.sleep(700)
            val uri = FileProvider.getUriForFile(ctx, "com.faceclean.wasuporte.arquivos", arq)
            ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            SuporteAcessibilidade.armar(pkg)
            val abriu = try {
                ctx.startActivity(Intent(Intent.ACTION_SEND).apply {
                    setPackage(pkg)
                    type = if (mime.contains("png")) "image/png" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    if (legenda.isNotBlank()) putExtra(Intent.EXTRA_TEXT, legenda)
                    putExtra("jid", "$dig@s.whatsapp.net")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                true
            } catch (e: Exception) { false }
            if (!abriu) { SuporteAcessibilidade.desarmar(); return "falhou" to "whatsapp_nao_abriu" }
            val clicou = SuporteAcessibilidade.esperar(25_000)
            Thread.sleep(if (clicou) 3000 else 800)
            acc.home()
            if (telaEstavaApagada) { Thread.sleep(600); acc.bloquear() }
            return if (clicou) "enviado" to "" else "falhou" to "nao_achou_botao_enviar"
        } finally {
            Tela.soltar()
        }
    }

    fun pacoteWhatsApp(ctx: Context): String? {
        for (p in listOf("com.whatsapp.w4b", "com.whatsapp")) {
            try { ctx.packageManager.getPackageInfo(p, 0); return p } catch (_: Exception) {}
        }
        return null
    }
}

/** Acende a tela só na hora de mandar foto. Funciona com bloqueio "Nenhum" ou "Deslizar"; com PIN não dá. */
object Tela {

    private var wl: PowerManager.WakeLock? = null
    @Volatile private var latch: CountDownLatch? = null

    fun ligada(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    fun temSenha(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    @Suppress("DEPRECATION")
    fun acordar(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        val km = ctx.getSystemService(KeyguardManager::class.java) ?: return false
        synchronized(this) {
            if (wl?.isHeld != true) {
                wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "wasuporte:tela")
                    .apply { setReferenceCounted(false); acquire(90_000) }
            }
        }
        Thread.sleep(600)
        if (!km.isKeyguardLocked) return true
        if (km.isDeviceSecure) return false
        val l = CountDownLatch(1)
        latch = l
        try {
            ctx.startActivity(Intent(ctx, AcordaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            l.await(8, TimeUnit.SECONDS)
        } catch (_: Exception) {}
        Thread.sleep(400)
        return !km.isKeyguardLocked
    }

    fun sinal() { latch?.countDown() }

    fun soltar() {
        synchronized(this) {
            try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
            wl = null
        }
    }
}

class AcordaActivity : Activity() {
    private var fim = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { pronto(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = pronto()
                override fun onDismissError() = pronto()
                override fun onDismissCancelled() = pronto()
            })
            window.decorView.postDelayed({ pronto() }, 5000)
        } else window.decorView.postDelayed({ pronto() }, 800)
    }

    private fun pronto() {
        if (fim) return
        fim = true
        Tela.sinal()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
