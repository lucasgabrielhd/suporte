package com.faceclean.wasuporte

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var loop: Runnable? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var testResult: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(2))
    }.also { root.addView(it) }

    private fun field(v: String, hint: String, secret: Boolean = false) = EditText(this).apply {
        setText(v); this.hint = hint; textSize = 14f
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }.also { root.addView(it) }

    private fun check(t: String, v: Boolean) = CheckBox(this).apply { text = t; isChecked = v }.also { root.addView(it) }

    private fun button(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }.also { root.addView(it) }

    private fun text(size: Float = 13f) = TextView(this).apply { textSize = size; setPadding(0, dp(6), 0, dp(6)); setTextIsSelectable(true) }.also { root.addView(it) }

    private fun sep() = View(this).apply {
        setBackgroundColor(0x33888888)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(18); bottomMargin = dp(4) }
    }.also { root.addView(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Engine.init(this)
        Watchdog.schedule(this)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(20), dp(18), dp(40)) }
        setContentView(ScrollView(this).apply { addView(root) })

        TextView(this).apply { text = "WA Suporte FC  v${Sys.appVersion(this@MainActivity)}"; textSize = 20f; setTypeface(typeface, Typeface.BOLD) }.also { root.addView(it) }
        TextView(this).apply { text = "As mensagens deste WhatsApp vão pro painel do suporte, e o que for digitado lá é respondido por aqui."; textSize = 13f }.also { root.addView(it) }

        status = text(14f)

        sep()
        label("Endereço dos webhooks (n8n)")
        val urlF = field(prefs.baseUrl, Prefs.URL_PADRAO)
        TextView(this).apply { text = "Usa ${Prefs.ROTA_IN} e ${Prefs.ROTA_OUT}"; textSize = 11f }.also { root.addView(it) }
        label("Token do app (o mesmo da credencial \"WA Suporte App\" no n8n)")
        val tokenF = field(prefs.token, "cole o token", true)
        label("Nome deste celular")
        val devF = field(prefs.deviceName, "suporte")
        val enabledB = check("Ativar (enviar e receber pelo painel)", prefs.enabled)
        val awakeB = check("Manter acordado (celular fica no carregador)", prefs.keepAwake)

        sep()
        label("Áudio e foto")
        val mediaB = check("Mandar fotos e áudios pro painel", prefs.sendMedia)
        val audioB = check("Transcrever áudios (Groq)", prefs.audioEnabled)
        val groqF = field(prefs.groqToken, "token Groq gsk_...", true)

        val salvar = {
            prefs.baseUrl = urlF.text.toString()
            prefs.token = tokenF.text.toString()
            prefs.deviceName = devF.text.toString()
            prefs.enabled = enabledB.isChecked
            prefs.keepAwake = awakeB.isChecked
            prefs.sendMedia = mediaB.isChecked
            prefs.audioEnabled = audioB.isChecked
            prefs.groqToken = groqF.text.toString()
            if (prefs.enabled) SuporteListener.rescan()
            Engine.kick()
            Engine.kickPoll()
        }

        sep()
        button("Salvar") { salvar(); toast("Salvo") }
        button("Testar conexão com o n8n") {
            salvar(); testResult.text = "testando…"
            Thread { val r = Engine.testConnection(); ui.post { testResult.text = r } }.start()
        }
        button("Testar Groq") {
            salvar(); val tk = prefs.groqToken
            Thread { val r = Media.testGroq(tk); ui.post { testResult.text = r } }.start()
        }
        testResult = text()

        sep()
        label("Permissões (faça todas uma vez)")
        button("1. Info do app → ⋮ → Permitir configurações restritas") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("2. Acesso a notificações") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        button("3. Liberar bateria") { pedirBateria() }
        button("4. Acesso a arquivos (áudio e foto)") { pedirArquivos() }
        button("5. Contatos (número da cliente salva)") { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 43) }

        sep()
        label("Últimos eventos")
        logView = text(12f).apply { typeface = Typeface.MONOSPACE }

        if (!Numero.temContatos(this)) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 43)
    }

    override fun onResume() {
        super.onResume()
        val r = object : Runnable { override fun run() { atualizar(); ui.postDelayed(this, 2000) } }
        loop = r
        ui.post(r)
    }

    override fun onPause() {
        loop?.let { ui.removeCallbacks(it) }
        loop = null
        super.onPause()
    }

    private fun ok(b: Boolean) = if (b) "✅" else "❌"

    private fun atualizar() {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val poll = if (Engine.lastPollAt > 0) "${if (Engine.lastPollOk) "✅" else "❌"} ${fmt.format(Date(Engine.lastPollAt))}" else "—"
        status.text = buildString {
            append("${ok(prefs.enabled)} Ativado\n")
            append("${ok(prefs.token.isNotBlank())} Token configurado\n")
            append("${ok(Sys.hasNotificationAccess(this@MainActivity))} Acesso a notificações\n")
            append("${ok(SuporteListener.connected)} Ouvindo o WhatsApp\n")
            append("${ok(Sys.ignoringBattery(this@MainActivity))} Bateria sem restrição\n")
            append("${ok(Sys.hasFilesAccess(this@MainActivity))} Arquivos · ${ok(prefs.audioEnabled && prefs.groqToken.isNotBlank())} Transcrição\n")
            append("${ok(Numero.temContatos(this@MainActivity))} Contatos\n\n")
            append("Última conversa com o n8n: $poll · painel ${if (Engine.painelAtivo) "aberto" else "fechado"}\n")
            append("Conversas que dá pra responder: ${Engine.actionsCount()}\n")
            append("Na fila pro painel: ${Store.pendingCount}\n")
            append("Hoje: ${Store.count("cnt_in")} recebidas · ${Store.count("cnt_out")} enviadas · ${Store.count("cnt_outfail")} falhas")
            if (Engine.lastError.isNotBlank()) append("\nÚltimo erro: ${Engine.lastError}")
        }
        logView.text = Store.logLines().take(50).joinToString("\n").ifBlank { "sem eventos ainda" }
    }

    private fun pedirArquivos() {
        if (Sys.hasFilesAccess(this)) { toast("Acesso a arquivos já liberado ✓"); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 42)
    }

    @SuppressLint("BatteryLife")
    private fun pedirBateria() {
        if (Sys.ignoringBattery(this)) { toast("Bateria já liberada ✓"); return }
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
