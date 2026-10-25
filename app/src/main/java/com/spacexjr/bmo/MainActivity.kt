package com.spacexjr.bmo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.spacexjr.bmo.databinding.ActivityMainBinding
import com.spacexjr.bmo.face.BmoState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: BmoApp get() = application as BmoApp
    private val assistant: BmoAssistant get() = app.assistant
    private lateinit var gestureDetector: GestureDetector

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            assistant.startListening()
        } else {
            showStatus("Sem a permissão de microfone eu não consigo ouvir você.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                handleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                assistant.cancelEverything()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                openSettings()
            }
        })

        binding.face.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            gestureDetector.onTouchEvent(event)
        }
        binding.buttonSettings.setOnClickListener { openSettings() }
        binding.buttonKeyboard.setOnClickListener { showTextDialog() }

        val settings = app.settingsRepository.get()
        binding.face.setDefaultExpression(settings.defaultExpression)
        binding.face.setFaceScale(settings.faceScale)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    assistant.state.collect { state ->
                        binding.face.setState(state)
                    }
                }
                launch {
                    assistant.bubble.collect { updateBubble() }
                }
                launch {
                    assistant.listening.collect { updateBubble() }
                }
                launch {
                    assistant.status.collect { text ->
                        binding.status.text = text
                        if (text.isBlank()) {
                            binding.status.visibility = View.GONE
                        } else {
                            binding.status.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            val repo = app.settingsRepository
            if (repo.get().modelPath.isBlank()) {
                repo.findLocalModel(this@MainActivity)?.let { fallback ->
                    repo.setModelPath(fallback.absolutePath)
                    assistant.updateSettings(repo.get())
                }
            }
            if (repo.get().modelPath.isNotBlank()) {
                assistant.loadModelIfAvailable()
            } else {
                showStatus("Modelo não carregado · toque e segure no rosto para configurar")
            }
            assistant.prepareEngines()
        }
    }

    override fun onResume() {
        super.onResume()
        val settings = app.settingsRepository.get()
        assistant.updateSettings(settings)
        binding.face.setDefaultExpression(settings.defaultExpression)
        binding.face.setFaceScale(settings.faceScale)
        assistant.setForeground(true)
        hideSystemBars()
    }

    override fun onPause() {
        assistant.setForeground(false)
        super.onPause()
    }

    private fun handleTap() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        when {
            assistant.speaking.value -> assistant.stopSpeaking()
            assistant.listening.value -> assistant.stopListening()
            else -> assistant.startListening()
        }
    }

    private fun showTextDialog() {
        val input = EditText(this).apply {
            hint = "Escreva para o BMO"
            setSingleLine(false)
            minLines = 2
            setPadding(36, 24, 36, 24)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Conversar por texto")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar", null)
            .create()
        fun submit() {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                input.error = "Escreva alguma coisa"
                return
            }
            dialog.dismiss()
            assistant.submitText(text)
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEND || isEnter) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        dialog.show()
    }

    private fun updateBubble() {
        val text = assistant.bubble.value
        val showPartial = app.settingsRepository.get().showTranscript
        val visible = text.isNotBlank() && (!assistant.listening.value || showPartial)
        binding.bubble.visibility = if (visible) View.VISIBLE else View.GONE
        binding.bubble.text = text
    }

    private fun showStatus(text: String) {
        binding.status.text = text
        binding.status.visibility = View.VISIBLE
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root as FrameLayout)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.bubble.visibility == View.VISIBLE) {
            binding.bubble.visibility = View.GONE
        } else {
            super.onBackPressed()
        }
    }
}
