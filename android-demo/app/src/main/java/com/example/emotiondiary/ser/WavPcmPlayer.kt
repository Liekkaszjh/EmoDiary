package com.example.emotiondiary.ser

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.min

class WavPcmPlayer(private val sampleRate: Int = 16000) {
    private val minPlaybackGain = 2.2f
    private val maxPlaybackGain = 4.2f
    private val targetRms = 0.28f
    interface Listener {
        fun onStart(durationMs: Int)
        fun onProgress(positionMs: Int, durationMs: Int)
        fun onComplete()
        fun onError(message: String)
    }

    @Volatile
    private var sessionToken: Int = 0

    @Volatile
    var isPlaying: Boolean = false
        private set

    private var worker: Thread? = null

    fun play(file: File, listener: Listener) {
        stop()
        val myToken = synchronized(this) {
            sessionToken += 1
            sessionToken
        }

        worker = Thread {
            var audioTrack: AudioTrack? = null
            try {
                val pcm = WavUtils.readPcm16MonoWav(file)
                if (pcm.isEmpty()) {
                    listener.onError("音频数据为空")
                    return@Thread
                }
                val boosted = applyAdaptiveGainWithLimiter(pcm)
                val shorts = floatArrayToShorts(boosted)
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2048)

                audioTrack = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    minBuf,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                val durationMs = (shorts.size * 1000L / sampleRate).toInt().coerceAtLeast(1)
                isPlaying = true
                if (!isSessionActive(myToken)) return@Thread
                listener.onStart(durationMs)
                runCatching { audioTrack?.setVolume(1.0f) }
                audioTrack.play()

                var offset = 0
                var lastReported = 0
                val chunk = (minBuf / 2).coerceAtLeast(512)
                while (isSessionActive(myToken) && offset < shorts.size) {
                    val toWrite = min(chunk, shorts.size - offset)
                    val written = audioTrack.write(shorts, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        if (isSessionActive(myToken)) {
                            listener.onError("AudioTrack 写入失败: $written")
                        }
                        break
                    }
                    offset += written
                    val posMs = (offset * 1000L / sampleRate).toInt()
                    if (posMs - lastReported >= 120) {
                        lastReported = posMs
                        if (isSessionActive(myToken)) {
                            listener.onProgress(posMs, durationMs)
                        }
                    }
                }

                if (isSessionActive(myToken)) {
                    listener.onProgress(durationMs, durationMs)
                    listener.onComplete()
                }
            } catch (e: Exception) {
                if (isSessionActive(myToken)) {
                    listener.onError(e.message ?: "播放异常")
                }
            } finally {
                if (isSessionActive(myToken)) {
                    isPlaying = false
                }
                runCatching { audioTrack?.stop() }
                runCatching { audioTrack?.release() }
                audioTrack = null
            }
        }.apply { start() }
    }

    fun stop() {
        synchronized(this) {
            sessionToken += 1
        }
        val t = worker
        if (t != null && t.isAlive) {
            runCatching { t.join(800) }
        }
        worker = null
        isPlaying = false
    }

    private fun isSessionActive(token: Int): Boolean = token == sessionToken

    private fun applyAdaptiveGainWithLimiter(src: FloatArray): FloatArray {
        if (src.isEmpty()) return src
        var peak = 0f
        var squareSum = 0.0
        for (v in src) {
            val a = abs(v)
            if (a > peak) peak = a
            squareSum += (v * v).toDouble()
        }
        if (peak <= 1e-6f) return src

        val rms = sqrt(squareSum / src.size).toFloat().coerceAtLeast(1e-6f)
        val adaptiveGain = (targetRms / rms).coerceIn(minPlaybackGain, maxPlaybackGain)
        val safeGain = min(adaptiveGain, 0.98f / peak)
        val out = FloatArray(src.size)
        for (i in src.indices) {
            out[i] = (src[i] * safeGain).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun floatArrayToShorts(src: FloatArray): ShortArray {
        val out = ShortArray(src.size)
        for (i in src.indices) {
            val v = (src[i] * 32767f).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }
}
