package com.voiceboard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Keeps recording, API calls and committing a finished transcript independent of the keyboard UI. */
class VoiceInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val inputConnection: () -> InputConnection?,
    private val onState: (State) -> Unit,
    private val onMessage: (String, Boolean) -> Unit,
    private val onNeedSettings: () -> Unit,
    private val onCommitted: () -> Unit = {}
) {
    enum class State { IDLE, RECORDING, PROCESSING }

    private val handler = Handler(Looper.getMainLooper())
    private var audioFile: File? = null
    private var recorder: AudioRecorder? = null
    private var work: Job? = null
    private var generation = 0
    var state = State.IDLE
        private set

    init {
        // A previous process may have left recordings behind after a crash. Each recording gets a
        // unique file, so nothing active can be deleted here.
        context.cacheDir.listFiles { file -> file.name.startsWith(RECORDING_PREFIX) }
            ?.forEach { it.delete() }
    }

    fun toggle() = when (state) {
        State.IDLE -> start()
        State.RECORDING -> stopAndTranscribe()
        State.PROCESSING -> cancel()
    }

    /**
     * Aborts recording or an in-flight request immediately. [notify] = false is used by the
     * toolbar cancel button, which must return the panel to its normal state without a status.
     */
    fun cancel(notify: Boolean = true) {
        val wasActive = state != State.IDLE
        generation++
        handler.removeCallbacks(levelTick)
        work?.cancel(); work = null
        val file = audioFile
        audioFile = null
        recorder?.abort(); recorder = null
        // While PROCESSING the file belongs to the cancelled job, which deletes it in `finally`.
        if (state == State.RECORDING) file?.delete()
        setState(State.IDLE)
        if (wasActive && notify) onMessage(context.getString(R.string.status_cancelled), false)
    }

    fun dispose() = cancel(notify = false)

    private fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onMessage(context.getString(R.string.err_no_mic_permission), true); onNeedSettings(); return
        }
        if (Prefs.groqKey(context).isEmpty()) {
            onMessage(context.getString(R.string.err_no_groq_key), true); onNeedSettings(); return
        }
        generation++
        val file = File(context.cacheDir, RECORDING_PREFIX + System.nanoTime() + ".wav")
        val value = AudioRecorder(file)
        if (!value.start()) {
            file.delete()
            onMessage(value.failure ?: context.getString(R.string.err_mic_busy), true)
            return
        }
        audioFile = file
        recorder = value
        setState(State.RECORDING)
        handler.post(levelTick)
    }

    private fun stopAndTranscribe() {
        val value = recorder ?: return
        val sourceFile = audioFile ?: return
        handler.removeCallbacks(levelTick)
        recorder = null
        audioFile = null
        setState(State.PROCESSING)
        onMessage(context.getString(R.string.status_processing), false)
        val token = generation
        val groqKey = Prefs.groqKey(context)
        val groqModel = Prefs.groqModel(context)
        val language = Prefs.language(context)
        val deepseekKey = Prefs.deepseekKey(context)
        val deepseekModel = Prefs.deepseekModel(context)
        val prompt = Prefs.prompt(context)
        work = scope.launch {
            var fileToDelete: File? = sourceFile
            try {
                val file = withContext(Dispatchers.IO) { value.stop() }
                fileToDelete = file ?: sourceFile
                if (generation != token) return@launch
                if (file == null || value.durationMillis < AudioRecorder.MIN_MILLIS) {
                    setState(State.IDLE)
                    onMessage(value.failure ?: context.getString(R.string.status_too_short), value.failure != null)
                    return@launch
                }
                val transcript = withContext(Dispatchers.IO) {
                    SpeechApi.transcribe(file, groqKey, groqModel, language)
                }.trim()
                if (generation != token) return@launch
                if (transcript.isEmpty()) {
                    setState(State.IDLE)
                    onMessage(context.getString(R.string.status_empty), false)
                    return@launch
                }
                var output = transcript
                var warning: String? = null
                if (Prefs.useDeepseek(context)) {
                    if (deepseekKey.isEmpty()) {
                        warning = context.getString(R.string.warn_no_deepseek_key)
                    } else try {
                        if (generation != token) return@launch
                        onMessage(context.getString(R.string.status_polishing), false)
                        output = withContext(Dispatchers.IO) {
                            SpeechApi.polish(transcript, deepseekKey, deepseekModel, prompt)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        warning = context.getString(R.string.warn_deepseek_failed, e.message ?: e.javaClass.simpleName)
                    }
                }
                if (generation != token) return@launch
                commitResult(output)
                if (generation != token) return@launch
                setState(State.IDLE)
                onMessage(warning ?: context.getString(R.string.status_done), warning != null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == token) {
                    setState(State.IDLE)
                    onMessage(e.message ?: e.javaClass.simpleName, true)
                }
            } finally {
                fileToDelete?.delete()
                if (work === this.coroutineContext[Job]) work = null
            }
        }
    }

    private fun commitResult(text: String) {
        val connection = inputConnection() ?: run {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("VoiceBoard", text))
            onMessage(context.getString(R.string.copied_to_clipboard), false)
            onCommitted()
            return
        }
        var output = text
        if (Prefs.autoSpace(context)) {
            val before = connection.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && !before[0].isWhitespace() && output.firstOrNull()?.isWhitespace() != true) output = " $output"
        }
        connection.commitText(output, 1)
        onCommitted()
    }

    private val levelTick = object : Runnable {
        override fun run() {
            val value = recorder ?: return
            if (state != State.RECORDING) return
            value.failure?.let { failure ->
                generation++
                handler.removeCallbacks(this)
                recorder = null
                val file = audioFile
                audioFile = null
                value.abort()
                file?.delete()
                setState(State.IDLE)
                onMessage(failure, true)
                return
            }
            val seconds = value.elapsedMillis / 1000
            onMessage(context.getString(R.string.status_recording_short, seconds / 60, seconds % 60), false)
            if (value.elapsedMillis >= 180_000L) { stopAndTranscribe(); return }
            handler.postDelayed(this, 60L)
        }
    }

    private fun setState(value: State) { state = value; onState(value) }

    private companion object {
        const val RECORDING_PREFIX = "voiceboard-rec-"
    }
}
