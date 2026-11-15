package com.voiceboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.voiceboard.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Stored values, aligned with the labels shown in the language dropdown. */
    private val languageCodes = arrayOf("auto", "ru", "en")

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etLanguage.setSimpleItems(
            arrayOf(
                getString(R.string.lang_auto),
                getString(R.string.lang_ru),
                getString(R.string.lang_en)
            )
        )

        load()

        binding.btnEnableIme.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        binding.btnPickIme.setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
        binding.btnMicPermission.setOnClickListener {
            if (micGranted()) {
                openAppInfo()
            } else {
                requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        binding.btnResetPrompt.setOnClickListener {
            binding.etPrompt.setText(getString(R.string.default_prompt))
        }
        binding.btnCheckKeys.setOnClickListener {
            save()
            checkKeys()
        }
        binding.btnSave.setOnClickListener {
            save()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        // The keyboard reads preferences on every tap, so persisting on the way out is enough to
        // keep an edited key from being lost when the user swipes the app away.
        save()
        super.onPause()
    }

    // ---------------------------------------------------------------- state

    private fun load() {
        val prefs = Prefs.get(this)
        binding.etGroqKey.setText(prefs.getString(Prefs.GROQ_KEY, ""))
        binding.etDeepseekKey.setText(prefs.getString(Prefs.DEEPSEEK_KEY, ""))
        binding.etGroqModel.setText(Prefs.groqModel(this))
        binding.etDeepseekModel.setText(Prefs.deepseekModel(this))
        binding.swDeepseek.isChecked = Prefs.useDeepseek(this)
        binding.swAutoSpace.isChecked = Prefs.autoSpace(this)
        binding.etPrompt.setText(Prefs.prompt(this))

        val index = languageCodes.indexOf(Prefs.language(this)).coerceAtLeast(0)
        binding.etLanguage.setText(languageLabel(index), false)
    }

    private fun save() {
        val index = listOf(
            getString(R.string.lang_auto),
            getString(R.string.lang_ru),
            getString(R.string.lang_en)
        ).indexOf(binding.etLanguage.text.toString()).coerceAtLeast(0)

        Prefs.get(this).edit()
            .putString(Prefs.GROQ_KEY, binding.etGroqKey.text.toString().trim())
            .putString(Prefs.DEEPSEEK_KEY, binding.etDeepseekKey.text.toString().trim())
            .putString(
                Prefs.GROQ_MODEL,
                binding.etGroqModel.text.toString().trim()
                    .ifBlank { Prefs.DEFAULT_GROQ_MODEL })
            .putString(
                Prefs.DEEPSEEK_MODEL,
                binding.etDeepseekModel.text.toString().trim()
                    .ifBlank { Prefs.DEFAULT_DEEPSEEK_MODEL })
            .putBoolean(Prefs.USE_DEEPSEEK, binding.swDeepseek.isChecked)
            .putBoolean(Prefs.AUTO_SPACE, binding.swAutoSpace.isChecked)
            .putString(Prefs.LANGUAGE, languageCodes[index])
            .putString(Prefs.PROMPT, binding.etPrompt.text.toString().trim())
            .apply()
    }

    private fun languageLabel(index: Int): String = when (index) {
        1 -> getString(R.string.lang_ru)
        2 -> getString(R.string.lang_en)
        else -> getString(R.string.lang_auto)
    }

    private fun refreshStatus() {
        binding.stateMic.text = getString(
            if (micGranted()) R.string.state_mic_granted else R.string.state_mic_denied
        )
        binding.stateIme.text = getString(
            when {
                isDefaultIme() -> R.string.state_ime_active
                isImeEnabled() -> R.string.state_ime_enabled
                else -> R.string.state_ime_disabled
            }
        )
    }

    private fun micGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isDefaultIme(): Boolean {
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()
        return current.startsWith(packageName + "/")
    }

    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + packageName)
            )
        )
    }

    // ---------------------------------------------------------------- key check

    /**
     * Groq is only pinged, but DeepSeek gets a real one-sentence polish() run through the prompt
     * that is currently in the box. Seeing the rewritten sentence is the only honest answer to
     * "is my prompt doing anything" - a 200 from /models would not prove it.
     */
    private fun checkKeys() {
        val groq = Prefs.groqKey(this)
        val deepseek = Prefs.deepseekKey(this)
        val deepseekModel = Prefs.deepseekModel(this)
        val prompt = Prefs.prompt(this)
        val sample = getString(R.string.check_test_phrase)

        binding.checkResult.visibility = View.VISIBLE
        binding.checkResult.text = getString(R.string.check_running)
        binding.btnCheckKeys.isEnabled = false

        lifecycleScope.launch {
            val groqLine = "Groq: " + if (groq.isEmpty()) {
                getString(R.string.check_skipped)
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        SpeechApi.checkGroqKey(groq)
                        "OK"
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                }
            }
            binding.checkResult.text = groqLine + "\n" + getString(R.string.check_running)

            val deepseekLine = "DeepSeek: " + if (deepseek.isEmpty()) {
                getString(R.string.check_skipped)
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        SpeechApi.polish(sample, deepseek, deepseekModel, prompt)
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                }
            }

            binding.checkResult.text = groqLine + "\n" + deepseekLine +
                "\n\n" + getString(R.string.check_test_input, sample)
            binding.btnCheckKeys.isEnabled = true
        }
    }
}
