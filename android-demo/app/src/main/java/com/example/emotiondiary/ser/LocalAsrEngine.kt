package com.example.emotiondiary.ser

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

class LocalAsrEngine(private val context: Context) {
    private var recognizer: OfflineRecognizer? = null

    private fun ensureRecognizer(): OfflineRecognizer {
        synchronized(this) {
            val cached = recognizer
            if (cached != null) return cached

            val modelDir = "asr/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
            val config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.int8.onnx",
                        language = "zh",
                        useInverseTextNormalization = true
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    debug = false
                )
            )

            val r = OfflineRecognizer(context.assets, config)
            recognizer = r
            return r
        }
    }

    fun transcribe(wavFile: File): String {
        if (!wavFile.exists()) return "[识别错误] 找不到音频文件"

        val samples = WavUtils.readPcm16MonoWav(wavFile)
        if (samples.isEmpty()) return "[无识别结果]"

        return runCatching {
            val r = ensureRecognizer()
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, 16000)
                r.decode(stream)
                val text = r.getResult(stream).text.orEmpty()
                val cleaned = cleanText(text)
                if (cleaned.isBlank()) "[无识别结果]" else cleaned
            } finally {
                stream.release()
            }
        }.getOrElse { "[识别错误] ${it.message}" }
    }

    fun release() {
        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun cleanText(text: String): String {
        // Match asr_infer.py post behavior: remove markers like <|zh|> and collapse spaces.
        return text
            .replace(Regex("<\\|.*?\\|>"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
