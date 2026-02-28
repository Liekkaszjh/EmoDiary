package com.example.emotiondiary.ser

import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MfccExtractor(
    private val sampleRate: Int = 16000,
    private val segLen: Int = (1.8f * 16000).toInt(),      // 28800
    private val segStep: Int = ((1.8f - 1.6f) * 16000).toInt(), // 3200
    private val frameLen: Int = 640,
    private val frameStep: Int = 512,
    private val nfft: Int = 1024,
    private val numCep: Int = 26,
    private val numFilters: Int = 26,
    private val targetFrames: Int = 57
) {
    private val eps = 1e-10
    private val hamming = FloatArray(frameLen) { i ->
        (0.54 - 0.46 * cos(2.0 * Math.PI * i / (frameLen - 1))).toFloat()
    }
    private val melFilters = buildMelFilterBank()
    private val fft = DoubleFFT_1D(nfft.toLong())

    fun extractForModel(wav: FloatArray): Array<FloatArray> {
        val segs = segmentWithPadding(wav)
        if (segs.isEmpty()) return emptyArray()
        return Array(segs.size) { idx ->
            val feat = mfccSegment(segs[idx]) // [26][57]
            flatten(feat)
        }
    }

    private fun segmentWithPadding(wav: FloatArray): List<FloatArray> {
        if (wav.isEmpty()) return emptyList()
        var x = wav
        if (x.size < segLen) {
            val rep = ceil(segLen.toDouble() / x.size.toDouble()).toInt()
            val y = FloatArray(x.size * rep)
            for (i in y.indices) y[i] = x[i % x.size]
            x = y
        }
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i + segLen <= x.size) {
            out.add(x.copyOfRange(i, i + segLen))
            i += segStep
        }
        if (out.isEmpty()) out.add(x.copyOfRange(0, min(segLen, x.size)))
        return out
    }

    private fun mfccSegment(seg: FloatArray): Array<FloatArray> {
        val pre = FloatArray(seg.size)
        if (seg.isNotEmpty()) {
            pre[0] = seg[0]
            for (i in 1 until seg.size) pre[i] = seg[i] - 0.97f * seg[i - 1]
        }

        val frameCount = if (pre.size < frameLen) 1 else (1 + floor((pre.size - frameLen).toDouble() / frameStep).toInt())
        val cepByFrame = ArrayList<FloatArray>(frameCount)

        for (fi in 0 until frameCount) {
            val start = fi * frameStep
            val frame = FloatArray(frameLen)
            for (j in 0 until frameLen) {
                val idx = start + j
                frame[j] = (if (idx < pre.size) pre[idx] else 0f) * hamming[j]
            }
            cepByFrame.add(mfccFrame(frame))
        }

        val t = cepByFrame.size
        val feat = Array(numCep) { FloatArray(t) }
        for (ti in 0 until t) {
            val c = cepByFrame[ti]
            for (ci in 0 until numCep) feat[ci][ti] = c[ci]
        }
        return padOrTrim(feat, targetFrames)
    }

    private fun mfccFrame(frame: FloatArray): FloatArray {
        val inComplex = DoubleArray(2 * nfft)
        var energy = 0.0
        for (i in frame.indices) {
            val v = frame[i].toDouble()
            energy += v * v
            inComplex[i] = v
        }
        fft.realForwardFull(inComplex)

        val power = DoubleArray(nfft / 2 + 1)
        for (k in 0..(nfft / 2)) {
            val re = inComplex[2 * k]
            val im = inComplex[2 * k + 1]
            power[k] = (re * re + im * im) / nfft
        }

        val logMel = DoubleArray(numFilters)
        for (m in 0 until numFilters) {
            var s = 0.0
            val f = melFilters[m]
            for (k in f.indices) s += power[k] * f[k]
            logMel[m] = ln(max(eps, s))
        }

        val cep = FloatArray(numCep)
        for (c in 0 until numCep) {
            var v = 0.0
            for (m in 0 until numFilters) {
                v += logMel[m] * cos(Math.PI * c * (m + 0.5) / numFilters)
            }
            cep[c] = v.toFloat()
        }
        cep[0] = ln(max(eps, energy)).toFloat()
        return cep
    }

    private fun buildMelFilterBank(): Array<DoubleArray> {
        val nfreq = nfft / 2 + 1
        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(numFilters + 2) { i ->
            lowMel + (highMel - lowMel) * i / (numFilters + 1)
        }
        val hzPoints = melPoints.map { melToHz(it) }
        val bins = IntArray(numFilters + 2) { i ->
            floor((nfft + 1) * hzPoints[i] / sampleRate).toInt().coerceIn(0, nfreq - 1)
        }
        val fb = Array(numFilters) { DoubleArray(nfreq) }
        for (m in 1..numFilters) {
            val left = bins[m - 1]
            val center = bins[m]
            val right = bins[m + 1]
            for (k in left until center) {
                val den = max(1, center - left).toDouble()
                fb[m - 1][k] = (k - left) / den
            }
            for (k in center until right) {
                val den = max(1, right - center).toDouble()
                fb[m - 1][k] = (right - k) / den
            }
        }
        return fb
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun padOrTrim(feat: Array<FloatArray>, t: Int): Array<FloatArray> {
        val c = feat.size
        val curT = if (c == 0) 0 else feat[0].size
        if (curT == t) return feat
        if (curT > t) {
            val start = (curT - t) / 2
            return Array(c) { ci ->
                feat[ci].copyOfRange(start, start + t)
            }
        }
        return Array(c) { ci ->
            FloatArray(t).also { out ->
                System.arraycopy(feat[ci], 0, out, 0, curT)
            }
        }
    }

    private fun flatten(feat: Array<FloatArray>): FloatArray {
        val c = feat.size
        val t = if (c == 0) 0 else feat[0].size
        val out = FloatArray(c * t)
        var k = 0
        for (i in 0 until c) {
            for (j in 0 until t) out[k++] = feat[i][j]
        }
        return out
    }
}
