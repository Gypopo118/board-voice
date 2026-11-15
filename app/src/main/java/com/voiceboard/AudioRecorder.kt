package com.voiceboard

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM16 straight to a RIFF/WAV file.
 *
 * MediaRecorder cannot produce WAV at all - it only writes container formats such as MP4/AAC or
 * 3GP/AMR - so the raw AudioRecord path is used instead. It also gives us a live amplitude value
 * for the pulsing microphone button.
 */
class AudioRecorder(private val output: File) {

    companion object {
        private const val TAG = "VoiceBoard/Rec"
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val HEADER_BYTES = 44

        /** Anything shorter than this is a mis-tap, not speech. */
        const val MIN_MILLIS = 400L
    }

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var startedAt = 0L

    @Volatile
    private var dataBytes = 0L

    /** Normalised 0f..1f microphone level, sampled by the UI to drive the pulse animation. */
    @Volatile
    var level: Float = 0f
        private set

    /** Set when the recorder dies on its own; surfaced to the user as a status line. */
    @Volatile
    var failure: String? = null
        private set

    val elapsedMillis: Long
        get() = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    val durationMillis: Long
        get() = dataBytes * 1000L / (SAMPLE_RATE * BYTES_PER_SAMPLE)

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            failure = "AudioRecord.getMinBufferSize=" + minBuffer
            return false
        }
        val bufferBytes = maxOf(minBuffer * 2, SAMPLE_RATE)

        // VOICE_RECOGNITION applies noise suppression tuned for speech and skips the AGC ramp that
        // MIC uses, so the first word is not swallowed. Not every device exposes it.
        val record = createRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, bufferBytes)
            ?: createRecord(MediaRecorder.AudioSource.MIC, bufferBytes)
        if (record == null) {
            failure = "AudioRecord init failed"
            return false
        }

        val file = try {
            output.parentFile?.mkdirs()
            RandomAccessFile(output, "rw").apply {
                setLength(0)
                write(ByteArray(HEADER_BYTES)) // placeholder, patched once the size is known
            }
        } catch (e: Exception) {
            record.release()
            failure = e.message ?: "cannot open output file"
            return false
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            file.close()
            failure = e.message ?: "startRecording failed"
            return false
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            file.close()
            failure = "microphone busy"
            return false
        }

        failure = null
        dataBytes = 0
        level = 0f
        startedAt = System.currentTimeMillis()
        running = true
        thread = Thread({ pump(record, file) }, "vb-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, bufferBytes: Int): AudioRecord? = try {
        val record = AudioRecord(
            source,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes
        )
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            record.release()
            null
        }
    } catch (e: Throwable) {
        Log.w(TAG, "audio source unavailable: " + source, e)
        null
    }

    private fun pump(record: AudioRecord, file: RandomAccessFile) {
        val samples = ShortArray(2048)
        val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
        var written = 0L
        try {
            while (running) {
                val read = record.read(samples, 0, samples.size)
                if (read <= 0) {
                    if (read < 0) {
                        failure = "AudioRecord.read=" + read
                        break
                    }
                    continue
                }
                var energy = 0.0
                var out = 0
                for (i in 0 until read) {
                    val sample = samples[i].toInt()
                    energy += sample.toDouble() * sample
                    bytes[out++] = (sample and 0xFF).toByte()
                    bytes[out++] = ((sample shr 8) and 0xFF).toByte()
                }
                file.write(bytes, 0, out)
                written += out
                dataBytes = written
                // RMS scaled up: normal speech sits well below full scale, so x4 keeps the ring
                // visibly reactive without clipping to 1f the whole time.
                val rms = sqrt(energy / read) / Short.MAX_VALUE
                level = min(1.0, rms * 4.0).toFloat()
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording loop failed", e)
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
            level = 0f
            try {
                record.stop()
            } catch (ignored: Exception) {
            }
            record.release()
            try {
                file.seek(0)
                file.write(wavHeader(written.toInt()))
            } catch (e: Exception) {
                Log.e(TAG, "cannot patch wav header", e)
                if (failure == null) failure = e.message
            }
            try {
                file.close()
            } catch (ignored: Exception) {
            }
            dataBytes = written
        }
    }

    /** Stops the loop and blocks until the WAV header has been written. */
    fun stop(): File? {
        if (!running && thread == null) return null
        running = false
        thread?.let {
            try {
                it.join(3000)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        startedAt = 0L
        return output.takeIf { it.exists() && it.length() > HEADER_BYTES }
    }

    /** Stops and throws the audio away - used when the user cancels or leaves the field. */
    fun abort() {
        stop()
        output.delete()
    }

    private fun wavHeader(dataLength: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLength)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                              // PCM fmt chunk size
            putShort(1)                             // PCM, uncompressed
            putShort(channels.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLength)
        }.array()
    }
}
