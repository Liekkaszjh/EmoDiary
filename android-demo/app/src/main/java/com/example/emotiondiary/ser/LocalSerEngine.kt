package com.example.emotiondiary.ser

import android.content.Context
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream

data class SerOutput(
    val label: String,
    val probs: Map<String, Float>
)

class LocalSerEngine(private val context: Context) {
    private val labels = arrayOf("Neutral", "Sad", "Angry", "Happy", "Fear", "Surprise")
    private val extractor = MfccExtractor()

    private val module: Module by lazy {
        val path = assetFilePath("models/ser_model_seed2022.ptl")
        LiteModuleLoader.load(path)
    }

    fun infer(wavFile: File): SerOutput {
        return runCatching {
            val wav = WavUtils.readPcm16MonoWav(wavFile)
            val feats = extractor.extractForModel(wav)
            if (feats.isEmpty()) return neutral()

            val n = feats.size
            val input = FloatArray(n * 26 * 57)
            for (i in 0 until n) {
                System.arraycopy(feats[i], 0, input, i * 26 * 57, 26 * 57)
            }
            val tensor = Tensor.fromBlob(input, longArrayOf(n.toLong(), 1, 26, 57))
            val out = module.forward(IValue.from(tensor)).toTensor().dataAsFloatArray
            if (out.isEmpty()) return neutral()

            val logits = FloatArray(labels.size)
            val segCount = out.size / labels.size
            for (s in 0 until segCount) {
                for (c in labels.indices) {
                    logits[c] += out[s * labels.size + c]
                }
            }
            val denom = segCount.coerceAtLeast(1).toFloat()
            for (c in labels.indices) logits[c] = logits[c] / denom

            val probsArr = softmax(logits)
            val probs = linkedMapOf<String, Float>()
            for (i in labels.indices) probs[labels[i]] = probsArr[i]
            val top = probs.maxByOrNull { it.value }?.key ?: "Neutral"
            SerOutput(label = top, probs = probs)
        }.getOrElse { neutral() }
    }

    private fun softmax(x: FloatArray): FloatArray {
        val max = x.maxOrNull() ?: 0f
        val exps = FloatArray(x.size)
        var sum = 0.0
        for (i in x.indices) {
            exps[i] = kotlin.math.exp((x[i] - max).toDouble()).toFloat()
            sum += exps[i]
        }
        if (sum <= 0.0) return FloatArray(x.size).also { if (it.isNotEmpty()) it[0] = 1f }
        for (i in exps.indices) {
            val v = (exps[i].toDouble() / sum).toFloat()
            exps[i] = v.coerceIn(0f, 1f)
        }
        return exps
    }

    private fun neutral(): SerOutput {
        val probs = linkedMapOf<String, Float>()
        labels.forEachIndexed { idx, s -> probs[s] = if (idx == 0) 1f else 0f }
        return SerOutput("Neutral", probs)
    }

    private fun assetFilePath(assetName: String): String {
        val outFile = File(context.filesDir, assetName.replace("/", "_"))
        if (outFile.exists() && outFile.length() > 0) return outFile.absolutePath
        context.assets.open(assetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile.absolutePath
    }
}
