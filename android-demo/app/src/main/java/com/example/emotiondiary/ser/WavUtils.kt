package com.example.emotiondiary.ser

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    fun finalizePcm16MonoWav(file: File, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16) {
        val totalAudioLen = (file.length() - 44).coerceAtLeast(0)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = totalAudioLen + 36

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeIntLE(raf, totalDataLen.toInt())
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1) // PCM
            writeShortLE(raf, channels.toShort().toInt())
            writeIntLE(raf, sampleRate)
            writeIntLE(raf, byteRate)
            writeShortLE(raf, (channels * bitsPerSample / 8))
            writeShortLE(raf, bitsPerSample)
            raf.writeBytes("data")
            writeIntLE(raf, totalAudioLen.toInt())
        }
    }

    fun readPcm16MonoWav(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return FloatArray(0)
        val pcm = bytes.copyOfRange(44, bytes.size)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(pcm.size / 2)
        var i = 0
        while (bb.remaining() >= 2) {
            out[i++] = bb.short / 32768.0f
        }
        return out
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
        raf.write((v shr 16) and 0xff)
        raf.write((v shr 24) and 0xff)
    }

    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xff)
        raf.write((v shr 8) and 0xff)
    }
}
