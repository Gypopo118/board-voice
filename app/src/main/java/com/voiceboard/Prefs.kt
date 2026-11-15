package com.voiceboard

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Single shared preference store for the whole app.
 *
 * The IME service and [SettingsActivity] live in the same process, so they get the very same
 * instance back and see each other's writes immediately - no cross-process listener needed.
 *
 * Encryption is best effort: a few OEM builds have a broken keystore, and a restored backup can
 * leave an undecryptable file behind. Both cases fall back to plain preferences instead of
 * crashing the keyboard, which would leave the user unable to type at all.
 */
object Prefs {

    const val GROQ_KEY = "groq_key"
    const val DEEPSEEK_KEY = "deepseek_key"
    const val GROQ_MODEL = "groq_model"
    const val DEEPSEEK_MODEL = "deepseek_model"
    const val USE_DEEPSEEK = "use_deepseek"
    const val LANGUAGE = "language"
    const val AUTO_SPACE = "auto_space"
    const val PROMPT = "prompt"

    const val DEFAULT_GROQ_MODEL = "whisper-large-v3-turbo"
    const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

    private const val SECURE_FILE = "vb_secure"
    private const val PLAIN_FILE = "vb_plain"

    @Volatile
    private var cached: SharedPreferences? = null

    fun get(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: open(context.applicationContext).also { cached = it }
        }
    }

    private fun open(context: Context): SharedPreferences = try {
        encrypted(context)
    } catch (first: Throwable) {
        try {
            context.deleteSharedPreferences(SECURE_FILE)
            encrypted(context)
        } catch (second: Throwable) {
            context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun encrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun groqKey(context: Context): String = get(context).getString(GROQ_KEY, "").orEmpty().trim()

    fun deepseekKey(context: Context): String =
        get(context).getString(DEEPSEEK_KEY, "").orEmpty().trim()

    fun groqModel(context: Context): String =
        get(context).getString(GROQ_MODEL, DEFAULT_GROQ_MODEL).orEmpty()
            .ifBlank { DEFAULT_GROQ_MODEL }

    fun deepseekModel(context: Context): String =
        get(context).getString(DEEPSEEK_MODEL, DEFAULT_DEEPSEEK_MODEL).orEmpty()
            .ifBlank { DEFAULT_DEEPSEEK_MODEL }

    fun language(context: Context): String = get(context).getString(LANGUAGE, "auto").orEmpty()

    fun prompt(context: Context): String =
        get(context).getString(PROMPT, null)?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.default_prompt)

    fun useDeepseek(context: Context): Boolean = get(context).getBoolean(USE_DEEPSEEK, true)

    fun autoSpace(context: Context): Boolean = get(context).getBoolean(AUTO_SPACE, true)
}
