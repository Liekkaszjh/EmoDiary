package com.example.emotiondiary.ser

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class WavRecorder(private val sampleRate: Int = 16000) {
    var peakAmplitude: Int = 0
        private set

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var running = false
    private var outFile: File? = null
    private var outStream: FileOutputStream? = null

    fun start(file: File): Boolean {
        val channel = AudioFormat.CHANNEL_IN_MONO
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, format).coerceAtLeast(4096)

        val arVoice = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channel,
            format,
            minBuf
        )
        val ar = if (arVoice.state == AudioRecord.STATE_INITIALIZED) {
            arVoice
        } else {
            arVoice.release()
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channel,
                format,
                minBuf
            )
        }
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            return false
        }

        outFile = file
        outStream = FileOutputStream(file).apply {
            // Placeholder WAV header, finalize on stop.
            write(ByteArray(44))
        }
        peakAmplitude = 0
        audioRecord = ar
        running = true

        ar.startRecording()
        worker = Thread {
            val shortBuf = ShortArray(minBuf / 2)
            while (running) {
                val n = ar.read(shortBuf, 0, shortBuf.size)
                if (n > 0) {
                    val bytes = ByteArray(n * 2)
                    var j = 0
                    for (i in 0 until n) {
                        val s = shortBuf[i]
                        val a = abs(s.toInt())
                        if (a > peakAmplitude) peakAmplitude = a
                        bytes[j++] = (s.toInt() and 0xff).toByte()
                        bytes[j++] = ((s.toInt() shr 8) and 0xff).toByte()
                    }
                    outStream?.write(bytes)
                }
            }
        }.apply { start() }
        return true
    }

    fun stop(): File? {
        running = false
        worker?.join(2000)
        worker = null

        audioRecord?.runCatching {
            stop()
            release()
        }
        audioRecord = null

        outStream?.flush()
        outStream?.close()
        outStream = null

        val f = outFile
        if (f != null && f.exists()) {
            WavUtils.finalizePcm16MonoWav(f, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
        }
        outFile = null
        return f
    }
}
