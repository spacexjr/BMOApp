package com.spacexjr.bmo

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.caverock.androidsvg.SVG
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.spacexjr.bmo.databinding.ActivitySettingsBinding
import com.spacexjr.bmo.face.ExpressionManager
import com.spacexjr.bmo.face.SvgTemplateRenderer
import com.spacexjr.bmo.llm.ModelStatus
import com.spacexjr.bmo.speech.TextToSpeechManager
import com.spacexjr.bmo.speech.VoskModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val app: BmoApp get() = application as BmoApp
    private val assistant: BmoAssistant get() = app.assistant
    private val repo: com.spacexjr.bmo.data.SettingsRepository get() = app.settingsRepository

    private lateinit var modelStatusView: TextView
    private lateinit var modelPathView: TextView
    private lateinit var thinkingWarning: TextView
    private lateinit var ttsStatusView: TextView
    private lateinit var micStatusView: TextView
    private lateinit var systemStatusView: TextView
    private lateinit var diagnosticsView: TextView
    private lateinit var lastReplyView: TextView
    private lateinit var previewView: ImageView
    private lateinit var faceScaleLabel: TextView
    private var thinkingSwitch: MaterialSwitch? = null
    private var pendingWakeEnable = false
    private var busy = false

    private val expressions by lazy { ExpressionManager(assets) }
    private val previewRenderer by lazy { SvgTemplateRenderer(assets) }

    private val openModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this, "Microfone autorizado", Toast.LENGTH_SHORT).show()
            if (pendingWakeEnable) {
                repo.update { it.copy(wakeWordEnabled = true) }
                assistant.updateSettings(repo.get())
            }
        } else {
            Toast.makeText(this, "Permissão de microfone negada", Toast.LENGTH_LONG).show()
            repo.update { it.copy(wakeWordEnabled = false) }
            assistant.updateSettings(repo.get())
        }
        pendingWakeEnable = false
        refreshMicStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        buildUi()
        collectState()
    }

    override fun onResume() {
        super.onResume()
        assistant.setForeground(true)
        refreshMicStatus()
        refreshSystemStatus()
    }

    override fun onPause() {
        assistant.setForeground(false)
        super.onPause()
    }

    private fun buildUi() {
        val content = binding.content
        val settings = repo.get()

        // ---------- Modelo ----------
        val modelCard = card(content)
        title(modelCard, "Inteligência artificial")
        modelStatusView = body(modelCard, "Verificando modelo...")
        modelPathView = body(modelCard, "", small = true, monospace = true)
        modelPathView.setTextIsSelectable(true)
        thinkingWarning = body(modelCard, "", small = true)

        button(modelCard, "Selecionar arquivo GGUF") {
            openModel.launch(arrayOf("*/*"))
        }
        val modelButtons = row(modelCard)
        buttonIn(modelButtons, "Carregar / recarregar", weight = 1f) {
            if (busy) return@buttonIn
            busy = true
            lifecycleScope.launch {
                try {
                    val status = assistant.reloadModel()
                    if (status is ModelStatus.Error) {
                        toast(status.message)
                    }
                    renderModelStatus(status)
                } finally {
                    busy = false
                }
            }
        }
        buttonIn(modelButtons, "Descarregar", weight = 1f) {
            assistant.unloadModel()
            renderModelStatus(ModelStatus.NotLoaded)
        }

        thinkingSwitch = switchRow(
            modelCard,
            "Thinking",
            "Quando o template do Qwen3 suporta, desativa o raciocínio interno com o bloco vazio de thinking.",
            settings.thinkingEnabled,
        ) { enabled ->
            repo.update { it.copy(thinkingEnabled = enabled) }
            assistant.updateSettings(repo.get())
            updateThinkingWarning()
        }

        slider(modelCard, "Limite de tokens por resposta", 16f, 256f, settings.maxTokens.toFloat(), 1f) { value ->
            repo.update { it.copy(maxTokens = value.toInt()) }
            assistant.updateSettings(repo.get())
        }
        slider(modelCard, "Tamanho do contexto", 256f, 2048f, settings.contextSize.toFloat(), 64f) { value ->
            repo.update { it.copy(contextSize = ((value.toInt() + 32) / 64) * 64) }
            assistant.updateSettings(repo.get())
        }
        slider(modelCard, "Threads de inferência", 1f, 6f, settings.threads.toFloat(), 1f) { value ->
            repo.update { it.copy(threads = value.toInt()) }
            assistant.updateSettings(repo.get())
        }
        body(modelCard, "Alterações de contexto, threads e modelo só valem depois de carregar novamente. O Q4_K_M usa menos RAM que o Q8_0.", small = true)

        // ---------- Voz ----------
        val voiceCard = card(content)
        title(voiceCard, "Voz")
        micStatusView = body(voiceCard, "Verificando permissão...")
        button(voiceCard, "Conceder permissão de microfone") {
            pendingWakeEnable = false
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        switchRow(
            voiceCard,
            "Reconhecimento de voz offline",
            "Vosk pt-BR local, sem enviar áudio para servidores.",
            settings.speechRecognitionEnabled,
        ) { enabled ->
            repo.update { it.copy(speechRecognitionEnabled = enabled) }
            assistant.updateSettings(repo.get())
        }
        switchRow(
            voiceCard,
            "Síntese de voz offline",
            "Piper VITS pt-BR (Faber), executado no próprio aparelho.",
            settings.speechSynthesisEnabled,
        ) { enabled ->
            repo.update { it.copy(speechSynthesisEnabled = enabled) }
            assistant.updateSettings(repo.get())
        }
        switchRow(
            voiceCard,
            "Palavra de ativação “Ei, BMO”",
            "Detector local por gramática Vosk. Funciona com o app aberto, consome bateria e não é um detector neural dedicado.",
            settings.wakeWordEnabled,
        ) { enabled ->
            if (enabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingWakeEnable = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                repo.update { it.copy(wakeWordEnabled = enabled) }
                assistant.updateSettings(repo.get())
            }
        }

        ttsStatusView = body(voiceCard, "Síntese: não inicializada", small = true)
        slider(voiceCard, "Velocidade da fala", 50f, 180f, settings.ttsSpeed * 100f, 5f) { value ->
            val speed = value / 100f
            repo.update { it.copy(ttsSpeed = speed) }
            assistant.updateSettings(repo.get())
        }
        slider(voiceCard, "Volume da voz", 0f, 100f, settings.ttsVolume * 100f, 5f) { value ->
            repo.update { it.copy(ttsVolume = value / 100f) }
            assistant.updateSettings(repo.get())
        }
        val voiceButtons = row(voiceCard)
        buttonIn(voiceButtons, "Testar voz", weight = 1f) {
            val text = "Oi! Eu sou o BMO. Minha voz está sendo gerada aqui no aparelho."
            assistant.speak(text)
        }
        buttonIn(voiceButtons, "Parar fala", weight = 1f) {
            assistant.stopSpeaking()
        }
        body(voiceCard, "Voz local disponível: Piper pt-BR Faber (média). O APK inclui este único modelo de voz; não há seletor fictício de vozes.", small = true)

        lastReplyView = body(voiceCard, "", small = true)
        button(voiceCard, "Conversar por texto (diagnóstico)") { showTextDialog() }

        // ---------- Aparência ----------
        val lookCard = card(content)
        title(lookCard, "Aparência")
        previewView = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)
            )
            setBackgroundColor(Color.rgb(144, 229, 200))
        }
        lookCard.addView(previewView)

        body(lookCard, "Expressão padrão", small = false)
        val spinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                expressions.availableExpressions(),
            )
        }
        lookCard.addView(spinner)
        val names = expressions.availableExpressions()
        spinner.setSelection(names.indexOf(settings.defaultExpression).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = names[position]
                repo.update { it.copy(defaultExpression = name) }
                assistant.updateSettings(repo.get())
                renderPreview(name)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        renderPreview(settings.defaultExpression)

        faceScaleLabel = body(lookCard, "Escala do rosto: ${"%.0f".format(settings.faceScale * 100)}%", small = true)
        val scale = SeekBar(this).apply {
            max = 50
            progress = ((settings.faceScale - 0.7f) / 0.5f * 50).toInt().coerceIn(0, 50)
        }
        scale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = 0.7f + progress / 50f * 0.5f
                faceScaleLabel.text = "Escala do rosto: ${"%.0f".format(value * 100)}%"
                repo.update { it.copy(faceScale = value) }
                assistant.updateSettings(repo.get())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        lookCard.addView(scale)
        body(lookCard, "As imagens são as SVGs originais em assets/faces, sem redesenho.", small = true)

        // ---------- Sistema ----------
        val systemCard = card(content)
        title(systemCard, "Sistema")
        systemStatusView = body(systemCard, "", small = true, monospace = true)
        button(systemCard, "Atualizar informações") { refreshSystemStatus() }
        button(systemCard, "Abrir permissões do aplicativo") {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        }

        body(systemCard, "Diagnóstico recente", small = false)
        diagnosticsView = body(systemCard, "", small = true, monospace = true)
        diagnosticsView.setTextIsSelectable(true)

        refreshMicStatus()
        refreshSystemStatus()
        renderModelStatus(assistant.modelStatus.value)
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    assistant.modelStatus.collect { renderModelStatus(it) }
                }
                launch {
                    assistant.ttsStatus.collect { status ->
                        if (::ttsStatusView.isInitialized) {
                            ttsStatusView.text = when (status) {
                                is TextToSpeechManager.Status.NotReady -> "Síntese: não inicializada"
                                is TextToSpeechManager.Status.Loading -> "Síntese: carregando modelo local..."
                                is TextToSpeechManager.Status.Ready -> "Síntese: pronta (${status.sampleRate} Hz, pt-BR)"
                                is TextToSpeechManager.Status.Error -> "Síntese: erro — ${status.message}"
                            }
                        }
                    }
                }
                launch {
                    assistant.bubble.collect { text ->
                        if (::lastReplyView.isInitialized) {
                            lastReplyView.text = if (text.isBlank()) "" else "Última resposta do BMO:\n$text"
                        }
                    }
                }
                launch {
                    app.diagnostics.entries.collect { entries ->
                        if (::diagnosticsView.isInitialized) {
                            diagnosticsView.text = entries.takeLast(30).joinToString("\n").ifBlank { "Sem eventos." }
                        }
                    }
                }
            }
        }
    }

    private fun renderModelStatus(status: ModelStatus) {
        if (!::modelStatusView.isInitialized) return
        val path = repo.get().modelPath
        modelPathView.text = if (path.isBlank()) "Nenhum arquivo selecionado" else path
        modelStatusView.text = when (status) {
            is ModelStatus.NotLoaded -> "Modelo: não carregado"
            is ModelStatus.Loading -> "Modelo: carregando..."
            is ModelStatus.Ready -> "Modelo: ${status.name}\n${status.info}"
            is ModelStatus.Error -> "Modelo: erro\n${status.message}"
        }
        if (status is ModelStatus.Ready) {
            thinkingSwitch?.isEnabled = true
        } else {
            thinkingSwitch?.isEnabled = true
        }
        updateThinkingWarning()
    }

    private fun updateThinkingWarning() {
        if (!::thinkingWarning.isInitialized) return
        val status = assistant.modelStatus.value
        val enabled = repo.get().thinkingEnabled
        thinkingWarning.text = when {
            status is ModelStatus.Ready && !status.supportsThinking ->
                "Aviso: o template deste GGUF não expõe enable_thinking. Com Thinking desligado o app usa /no_think; o comportamento depende do modelo."
            status is ModelStatus.Ready && !enabled ->
                "Thinking desativado: o app injeta o bloco vazio exatamente como o template Qwen3 com enable_thinking=false."
            status is ModelStatus.Ready && enabled ->
                "Thinking ativado: o modelo pode gastar mais tokens antes de responder."
            else -> "A compatibilidade com Thinking é verificada no template do modelo depois do carregamento."
        }
    }

    private fun refreshMicStatus() {
        if (!::micStatusView.isInitialized) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        micStatusView.text = if (granted) {
            "Permissão de microfone: concedida. Palavra de ativação: ${if (assistant.wakeWordRunning()) "ativa" else "parada"}."
        } else {
            "Permissão de microfone: não concedida."
        }
    }

    private fun refreshSystemStatus() {
        if (!::systemStatusView.isInitialized) return
        val activityManager = getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()
        val javaUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val debug = Debug.MemoryInfo()
        Debug.getMemoryInfo(debug)
        val pss = debug.totalPss / 1024
        val tts = when (val s = assistant.ttsStatus.value) {
            is TextToSpeechManager.Status.Ready -> "pronta"
            is TextToSpeechManager.Status.Loading -> "carregando"
            is TextToSpeechManager.Status.Error -> "erro"
            else -> "não inicializada"
        }
        systemStatusView.text = buildString {
            appendLine("Versão do app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("RAM do sistema livre: ${memoryInfo.availMem / (1024 * 1024)} MB de ${memoryInfo.totalMem / (1024 * 1024)} MB")
            appendLine("Memória Java usada: $javaUsed MB · PSS do app: $pss MB")
            appendLine("Modelo: ${when (val s = assistant.modelStatus.value) {
                is ModelStatus.Ready -> s.name
                is ModelStatus.Loading -> "carregando"
                is ModelStatus.Error -> "erro"
                else -> "não carregado"
            }}")
            appendLine("Thinking suportado pelo template: ${if (assistant.modelTemplateSupportsThinking()) "sim" else "não verificado/indisponível"}")
            appendLine("ASR Vosk local: ${if (assistant.voskModelLoaded()) "carregado" else "em repouso"}")
            appendLine("TTS local: $tts")
            appendLine("Palavra de ativação: ${if (assistant.wakeWordRunning()) "rodando" else "parada"} (somente com o app aberto)")
        }
    }

    private fun showTextDialog() {
        val input = EditText(this).apply {
            hint = "Escreva para o BMO"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Conversar por texto")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    input.error = "Escreva alguma coisa"
                    return@setOnClickListener
                }
                dialog.dismiss()
                assistant.submitText(text)
            }
        }
        dialog.show()
    }

    private fun importModel(uri: Uri) {
        val name = queryName(uri) ?: "modelo.gguf"
        val size = querySize(uri)
        val free = StatFs(getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath).availableBytes
        val message = buildString {
            append("Arquivo: $name\n")
            if (size > 0) append("Tamanho: ${size / (1024 * 1024)} MB\n")
            append("Espaço livre no armazenamento do app: ${free / (1024 * 1024)} MB\n\n")
            append("O arquivo será copiado para o armazenamento privado do BMO. Nada será enviado para a internet.")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Importar modelo GGUF")
            .setMessage(message)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Copiar") { _, _ -> copyModel(uri, name, size) }
            .show()
    }

    private fun copyModel(uri: Uri, displayName: String, size: Long) {
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.gguf" }
        val dir = File(getExternalFilesDir(null), "models").apply { mkdirs() }
        val target = File(dir, safeName)
        val progressBar = ProgressBar(this).apply { isIndeterminate = true }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Copiando modelo")
            .setMessage("Preparando...")
            .setView(progressBar)
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            var success = false
            try {
                withContext(Dispatchers.IO) {
                    val temp = File(dir, "$safeName.part")
                    contentResolver.openInputStream(uri).use { input ->
                        if (input == null) throw IllegalStateException("Não foi possível abrir o arquivo selecionado.")
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(1 shl 20)
                            var copied = 0L
                            var lastReport = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                copied += read
                                if (size > 0 && copied - lastReport > 32L * 1024 * 1024) {
                                    lastReport = copied
                                    val percent = (copied * 100 / size).toInt()
                                    runOnUiThread { dialog.setMessage("Copiando... $percent%") }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) throw IllegalStateException("Não foi possível finalizar o arquivo.")
                    validateGguf(target)
                    success = true
                }
                dialog.setMessage("Modelo copiado. Carregando...")
                repo.setModelPath(target.absolutePath)
                assistant.updateSettings(repo.get())
                assistant.reloadModel()
                toast("Modelo importado: ${target.name}")
                renderModelStatus(assistant.modelStatus.value)
            } catch (t: Throwable) {
                toast("Falha ao importar: ${t.message}")
                app.diagnostics.error("Settings", "import model failed", t)
            } finally {
                dialog.dismiss()
                if (!success) {
                    File(dir, "$safeName.part").delete()
                }
            }
        }
    }

    private fun validateGguf(file: File) {
        if (file.length() < 1_000_000L) throw IllegalStateException("O arquivo é pequeno demais para ser um GGUF.")
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic) != "GGUF") throw IllegalStateException("O arquivo não é um GGUF válido.")
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun querySize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L
    } catch (_: Throwable) {
        0L
    }

    private fun renderPreview(name: String) {
        if (!::previewView.isInitialized) return
        try {
            val params = when (name) {
                "look_around" -> mapOf("x" to 0.0)
                "whistle", "sleeping" -> mapOf("t" to 0.3)
                else -> emptyMap()
            }
            val svgText = previewRenderer.render(name, params)
            val svg = SVG.getFromString(svgText)
            val width = 560
            val height = 420
            svg.setDocumentWidth(width.toString())
            svg.setDocumentHeight(height.toString())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(144, 229, 200))
            svg.renderToCanvas(canvas)
            previewView.setImageBitmap(bitmap)
        } catch (t: Throwable) {
            previewView.setImageDrawable(null)
            app.diagnostics.error("Preview", "falha ao renderizar $name", t)
        }
    }

    // ---------- small UI helpers ----------

    private fun card(parent: LinearLayout): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(225, 240, 234))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        parent.addView(layout)
        return layout
    }

    private fun title(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.rgb(11, 107, 85))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        })
    }

    private fun body(
        parent: LinearLayout,
        text: String,
        small: Boolean = false,
        monospace: Boolean = false,
    ): TextView {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(if (small) Color.rgb(91, 116, 107) else Color.rgb(16, 35, 29))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 12.5f else 14f)
            if (monospace) typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(4))
        }
        parent.addView(view)
        return view
    }

    private fun button(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val view = com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        parent.addView(view)
    }

    private fun buttonIn(parent: LinearLayout, text: String, weight: Float, onClick: () -> Unit) {
        val view = com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                .apply { marginEnd = dp(6) }
        }
        parent.addView(view)
    }

    private fun row(parent: LinearLayout): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        parent.addView(layout)
        return layout
    }

    private fun switchRow(
        parent: LinearLayout,
        titleText: String,
        summary: String,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): MaterialSwitch {
        val switch = MaterialSwitch(this).apply {
            text = titleText
            isChecked = checked
            showText = false
            setTextColor(Color.rgb(16, 35, 29))
        }
        val summaryView = TextView(this).apply {
            text = summary
            setTextColor(Color.rgb(91, 116, 107))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(4))
        }
        parent.addView(switch)
        parent.addView(summaryView)
        switch.setOnCheckedChangeListener { _, value -> onChange(value) }
        return switch
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        min: Float,
        max: Float,
        initial: Float,
        step: Float,
        onChange: (Float) -> Unit,
    ) {
        val labelView = TextView(this).apply {
            text = "$label: ${formatValue(initial, step)}"
            setTextColor(Color.rgb(16, 35, 29))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        }
        val seek = SeekBar(this).apply {
            this.max = ((max - min) / step).toInt().coerceAtLeast(1)
            progress = ((initial - min) / step).toInt().coerceIn(0, this.max)
        }
        val valueOf = { progress: Int -> (min + progress * step).coerceIn(min, max) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = valueOf(progress)
                labelView.text = "$label: ${formatValue(value, step)}"
                if (fromUser) onChange(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        parent.addView(labelView)
        parent.addView(seek)
    }

    private fun formatValue(value: Float, step: Float): String =
        if (step >= 1f) value.toInt().toString() else String.format(Locale.US, "%.2f", value)

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
