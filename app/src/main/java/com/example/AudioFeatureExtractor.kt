package com.example

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.ln

object AudioFeatureExtractor {

    private const val NUM_MEL_FILTERS = 26
    private const val NUM_MFCC_COEFFS = 13
    private const val FFT_SIZE = 512
    private const val SAMPLE_RATE = 16000

    // Lazy load the triangular Mel filter bank weights for maximum speed
    private val melFilters: Array<DoubleArray> by lazy {
        val filters = Array(NUM_MEL_FILTERS) { DoubleArray(FFT_SIZE / 2 + 1) }
        val minMel = hzToMel(100.0)
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        
        val melPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            minMel + i * (maxMel - minMel) / (NUM_MEL_FILTERS + 1)
        }
        
        val hzPoints = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            melToHz(melPoints[i])
        }
        
        val binPoints = IntArray(NUM_MEL_FILTERS + 2) { i ->
            Math.floor((FFT_SIZE + 1) * hzPoints[i] / SAMPLE_RATE).toInt()
                .coerceIn(0, FFT_SIZE / 2)
        }
        
        for (m in 1..NUM_MEL_FILTERS) {
            val fMinus = binPoints[m - 1]
            val fCenter = binPoints[m]
            val fPlus = binPoints[m + 1]
            
            for (k in fMinus until fCenter) {
                if (fCenter != fMinus) {
                    filters[m - 1][k] = (k - fMinus).toDouble() / (fCenter - fMinus)
                }
            }
            for (k in fCenter..fPlus) {
                if (fPlus != fCenter) {
                    filters[m - 1][k] = (fPlus - k).toDouble() / (fPlus - fCenter)
                }
            }
        }
        filters
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)
    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /**
     * Extracts a sequence of 13-dimensional MFCC vectors from a 16-bit signed PCM recording.
     * Applies Silence Trimming (VAD) and Cepstral Mean & Variance Normalization (CMVN).
     */
    fun extractMfccSequence(pcmBytes: ByteArray): List<FloatArray> {
        val floats = pcmToFloat(pcmBytes)
        if (floats.isEmpty()) return emptyList()

        // Step 1: Voice Activity Detection (VAD) / Silence Trimming
        val speech = trimSilence(floats)
        if (speech.isEmpty()) return emptyList()

        // Step 2: Overlapping frames of 25ms (400 samples) with 10ms step (160 samples)
        val frameLength = 400
        val stepSize = 160
        val numFrames = ((speech.size - frameLength) / stepSize).coerceAtLeast(0) + 1
        
        val sequence = ArrayList<FloatArray>(numFrames)

        for (f in 0 until numFrames) {
            val startIdx = f * stepSize
            val windowed = DoubleArray(frameLength)
            
            // Hamming window & copy samples
            for (n in 0 until frameLength) {
                val idx = startIdx + n
                val sample = if (idx < speech.size) speech[idx] else 0f
                val window = 0.54 - 0.46 * cos(2.0 * Math.PI * n / (frameLength - 1))
                windowed[n] = sample * window
            }

            // Pad to 512 for FFT
            val real = DoubleArray(FFT_SIZE) { i -> if (i < frameLength) windowed[i] else 0.0 }
            val imag = DoubleArray(FFT_SIZE) { 0.0 }
            
            fft(real, imag)

            // Power spectrum of first 257 bins (Nyquist limit)
            val powerSpectrum = DoubleArray(FFT_SIZE / 2 + 1) { i ->
                (real[i] * real[i] + imag[i] * imag[i]) / FFT_SIZE.toDouble()
            }

            // Map power spectrum onto 26 Mel Scale Filter Banks
            val melEnergies = DoubleArray(NUM_MEL_FILTERS)
            for (m in 0 until NUM_MEL_FILTERS) {
                var energy = 0.0
                for (k in 0..FFT_SIZE / 2) {
                    energy += powerSpectrum[k] * melFilters[m][k]
                }
                melEnergies[m] = ln(energy + 1e-10) // logarithmic compression
            }

            // Discrete Cosine Transform (DCT-II) to yield 13 MFCC coefficients
            val mfcc = FloatArray(NUM_MFCC_COEFFS)
            for (c in 0 until NUM_MFCC_COEFFS) {
                var sum = 0.0
                for (m in 0 until NUM_MEL_FILTERS) {
                    sum += melEnergies[m] * cos(Math.PI * c * (2.0 * m + 1.0) / (2.0 * NUM_MEL_FILTERS))
                }
                mfcc[c] = sum.toFloat()
            }
            sequence.add(mfcc)
        }

        // Step 3: Apply Cepstral Mean & Variance Normalization (CMVN)
        return performCmvn(sequence)
    }

    /**
     * Cooley-Tukey Radix-2 FFT (Fast Fourier Transform).
     */
    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        if (n <= 1) return

        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR
                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var size = 2
        while (size <= n) {
            val halfSize = size shr 1
            for (i in 0 until n step size) {
                for (k in 0 until halfSize) {
                    val angle = -2.0 * Math.PI * k / size
                    val wReal = cos(angle)
                    val wImag = sin(angle)
                    
                    val tReal = real[i + k + halfSize] * wReal - imag[i + k + halfSize] * wImag
                    val tImag = real[i + k + halfSize] * wImag + imag[i + k + halfSize] * wReal
                    
                    real[i + k + halfSize] = real[i + k] - tReal
                    imag[i + k + halfSize] = imag[i + k] - tImag
                    real[i + k] += tReal
                    imag[i + k] += tImag
                }
            }
            size = size shl 1
        }
    }

    /**
     * Converts a 16-bit signed PCM ByteArray (Little Endian) to a FloatArray in range [-1.0, 1.0].
     */
    private fun pcmToFloat(pcmBytes: ByteArray): FloatArray {
        val size = pcmBytes.size / 2
        val floats = FloatArray(size)
        for (i in 0 until size) {
            val low = pcmBytes[2 * i].toInt() and 0xFF
            val high = pcmBytes[2 * i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            floats[i] = sample.toFloat() / 32768.0f
        }
        return floats
    }

    /**
     * Trims leading and trailing silence based on frame-level RMS energy.
     */
    private fun trimSilence(audio: FloatArray): FloatArray {
        val windowSize = 160 // 10ms windows at 16kHz
        val numWindows = audio.size / windowSize
        if (numWindows == 0) return audio

        val energies = DoubleArray(numWindows)
        var maxEnergy = 0.0

        for (w in 0 until numWindows) {
            var sumSquares = 0.0
            val start = w * windowSize
            for (n in 0 until windowSize) {
                val sample = audio[start + n]
                sumSquares += sample * sample
            }
            val rms = sqrt(sumSquares / windowSize)
            energies[w] = rms
            if (rms > maxEnergy) {
                maxEnergy = rms
            }
        }

        // Speech activation threshold (10% of peak or 0.005 absolute floor)
        val threshold = (0.10 * maxEnergy).coerceAtLeast(0.005)

        var firstActive = -1
        var lastActive = -1

        for (w in 0 until numWindows) {
            if (energies[w] >= threshold) {
                if (firstActive == -1) {
                    firstActive = w
                }
                lastActive = w
            }
        }

        if (firstActive == -1) {
            return audio // No active speech found, return original
        }

        // Pad slightly to preserve syllable onset/offset
        val startPadding = (firstActive - 2).coerceAtLeast(0)
        val endPadding = (lastActive + 2).coerceAtMost(numWindows - 1)

        val startSample = startPadding * windowSize
        val endSample = (endPadding + 1) * windowSize

        return audio.copyOfRange(startSample, endSample)
    }

    /**
     * Cepstral Mean and Variance Normalization (CMVN) for stable matching.
     */
    private fun performCmvn(sequence: List<FloatArray>): List<FloatArray> {
        if (sequence.isEmpty()) return sequence
        val numFrames = sequence.size
        val dim = sequence[0].size
        
        val mean = FloatArray(dim)
        val variance = FloatArray(dim)
        
        for (frame in sequence) {
            for (d in 0 until dim) {
                mean[d] += frame[d]
            }
        }
        for (d in 0 until dim) {
            mean[d] /= numFrames
        }
        
        for (frame in sequence) {
            for (d in 0 until dim) {
                val diff = frame[d] - mean[d]
                variance[d] += diff * diff
            }
        }
        for (d in 0 until dim) {
            variance[d] = sqrt(variance[d] / numFrames).coerceAtLeast(1e-5f)
        }
        
        return sequence.map { frame ->
            FloatArray(dim) { d ->
                (frame[d] - mean[d]) / variance[d]
            }
        }
    }

    /**
     * Euclidean Distance between two MFCC vectors.
     */
    fun euclideanDistance(v1: FloatArray, v2: FloatArray): Float {
        var sum = 0f
        for (i in v1.indices) {
            val diff = v1[i] - v2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /**
     * Computes the normalized Dynamic Time Warping (DTW) distance between two MFCC sequences.
     * Alignment is resilient to timing variations (elongation, fast speed, pauses).
     * Smaller values mean a closer match.
     */
    fun computeDtwDistance(seqA: List<FloatArray>, seqB: List<FloatArray>): Float {
        if (seqA.isEmpty() || seqB.isEmpty()) return Float.MAX_VALUE
        val n = seqA.size
        val m = seqB.size
        
        // DP matrix initialized to max values
        val dp = Array(n) { FloatArray(m) { Float.MAX_VALUE } }
        
        dp[0][0] = euclideanDistance(seqA[0], seqB[0])
        
        for (i in 1 until n) {
            dp[i][0] = dp[i - 1][0] + euclideanDistance(seqA[i], seqB[0])
        }
        
        for (j in 1 until m) {
            dp[0][j] = dp[0][j - 1] + euclideanDistance(seqA[0], seqB[j])
        }
        
        for (i in 1 until n) {
            for (j in 1 until m) {
                val cost = euclideanDistance(seqA[i], seqB[j])
                dp[i][j] = cost + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        
        // Normalized by path length (sum of dimensions) to handle varying durations
        return dp[n - 1][m - 1] / (n + m)
    }
}
