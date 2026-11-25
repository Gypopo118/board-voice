package com.voiceboard

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin blocking wrappers over the two HTTP endpoints. Callers are expected to be on Dispatchers.IO.
 */
object SpeechApi {

    private const val GROQ_TRANSCRIBE = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val GROQ_MODELS = "https://api.groq.com/openai/v1/models"
    private const val DEEPSEEK_CHAT = "https://api.deepseek.com/chat/completions"

    private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()

    /** A failure that already carries a message worth showing to the user verbatim. */
    class ApiException(message: String) : IOException(message)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Groq Whisper transcription. [language] is an ISO-639-1 code, or "auto" to let Whisper decide.
     */
    fun transcribe(audio: File, apiKey: String, model: String, language: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                audio.asRequestBody("audio/wav".toMediaTypeOrNull())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply { if (language != "auto") addFormDataPart("language", language) }
            .build()

        val request = Request.Builder()
            .url(GROQ_TRANSCRIBE)
            .header("Authorization", "Bearer " + apiKey)
            .post(body)
            .build()

        val raw = execute(request, "Groq")
        return JSONObject(raw).optString("text").trim()
    }

    /**
     * Sends text through DeepSeek to fix punctuation and casing. Returns the cleaned text, or
     * throws - the caller decides whether to fall back to the raw text. A lower temperature makes
     * the light-editing pass more deterministic.
     */
    fun polish(
        text: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        temperature: Double = 0.2
    ): String {
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", temperature)
            .put("stream", false)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt))
                    .put(JSONObject().put("role", "user").put("content", text))
            )

        val request = Request.Builder()
            .url(DEEPSEEK_CHAT)
            .header("Authorization", "Bearer " + apiKey)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val raw = execute(request, "DeepSeek")
        val content = JSONObject(raw)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()

        if (content.isEmpty()) throw ApiException("DeepSeek: empty response")
        // Models occasionally wrap the answer in quotes despite the prompt saying not to. Keep
        // the quotes when they are part of the author's text rather than a wrapper.
        val cleaned = removeModelQuotes(content, text)
        if (cleaned.isEmpty()) throw ApiException("DeepSeek: empty response")

        suspiciousReason(text, cleaned)?.let { throw ApiException(it) }
        return cleaned
    }

    private fun removeModelQuotes(value: String, original: String): String {
        val text = value.trim()
        val originalText = original.trim()
        if (text.length < 2) return text
        val pairs = listOf("\"" to "\"", "«" to "»", "\u201C" to "\u201D")
        for ((open, close) in pairs) {
            if (!text.startsWith(open) || !text.endsWith(close)) continue
            if (originalText.startsWith(open) && originalText.endsWith(close)) return text
            return text.substring(open.length, text.length - close.length).trim()
        }
        return text
    }

    /**
     * On garbled or noise-only audio, Whisper sometimes hallucinates something question-shaped,
     * and DeepSeek occasionally answers it instead of just fixing its punctuation - replying as
     * an assistant rather than editing the transcript. Returns null when [polished] looks like a
     * normal punctuation pass over [original], otherwise a short reason to fall back to the raw
     * Whisper text instead of committing whatever DeepSeek said.
     */
    private fun suspiciousReason(original: String, polished: String): String? {
        val lower = polished.lowercase()

        val selfReference = listOf(
            "языковая модель", "как ии", "как ассистент", "as an ai", "language model",
            "i'm an ai", "i am an ai", "openai", "deepseek", "anthropic", "chatgpt", "нейросеть"
        )
        if (selfReference.any { it in lower && it !in original.lowercase() }) {
            return "ответ похож на реплику модели, а не на правку текста"
        }

        val hasMarkdown = looksLikeMarkdown(polished)
        if (hasMarkdown && !looksLikeMarkdown(original)) {
            return "ответ содержит разметку, которой не было в исходном тексте"
        }

        val preambles = listOf(
            "вот исправленный текст", "исправленный текст:", "отредактированный текст:",
            "here is the corrected text", "here's the corrected text"
        )
        if (preambles.any { lower.startsWith(it) }) {
            return "ответ начинается со вступления, а не с текста"
        }

        if (polished.length > original.length * 3 + 40) {
            return "ответ намного длиннее исходной расшифровки"
        }
        if (original.length > 20 && polished.length < original.length * 0.25) {
            return "ответ намного короче исходной расшифровки"
        }

        return null
    }

    /** Rough markdown detector used to reject formatting that the model invented. */
    private fun looksLikeMarkdown(text: String): Boolean {
        if ("```" in text) return true
        if (text.lineSequence().any { it.trimStart().startsWith("#") }) return true
        return text.lineSequence().count { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
                Regex("""^\d+\.\s""").containsMatchIn(trimmed)
        } >= 2
    }

    /** Cheap authenticated GET used by the settings screen to validate a key. */
    fun checkGroqKey(apiKey: String) {
        execute(
            Request.Builder().url(GROQ_MODELS)
                .header("Authorization", "Bearer " + apiKey).get().build(),
            "Groq"
        )
    }

    // There is no cheap DeepSeek probe worth having: /models answers 200 even when the account
    // balance is zero, which is exactly the failure people hit. The settings screen runs a real
    // one-sentence polish() instead, so a broken key, an empty balance and a prompt that does
    // nothing all become visible in the same place.

    private fun execute(request: Request, label: String): String {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(label + " " + response.code + ": " + describe(raw, response.code))
            }
            return raw
        }
    }

    private fun describe(raw: String, code: Int): String {
        val fromJson = try {
            val error = JSONObject(raw).optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
        } catch (ignored: Exception) {
            null
        }
        if (fromJson != null) return fromJson
        if (raw.isNotBlank()) return raw.take(180)
        return when (code) {
            401 -> "invalid API key"
            402 -> "insufficient balance"
            429 -> "rate limited"
            else -> "request failed"
        }
    }
}
