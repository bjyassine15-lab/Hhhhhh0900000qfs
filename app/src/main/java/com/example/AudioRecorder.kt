package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class AudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startRecording(onDataAvailable: (ByteArray) -> Unit = {}, onError: (String) -> Unit) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            onError("فشل تهيئة مسجل الصوت (حجم تخزين غير صالح)")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("فشل تهيئة مسجل الصوت (الجهاز غير جاهز)")
                return
            }

            audioRecord?.startRecording()
            isRecording = true

            recordingJob = recordingScope.launch {
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(bufferSize)

                try {
                    while (isRecording) {
                        val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (readBytes > 0) {
                            outputStream.write(buffer, 0, readBytes)
                        }
                    }
                    onDataAvailable(outputStream.toByteArray())
                } catch (e: Exception) {
                    Log.e(TAG, "Error in recording loop", e)
                } finally {
                    outputStream.close()
                }
            }
        } catch (e: SecurityException) {
            onError("يجب السماح بالوصول إلى الميكروفون لاستخدام التطبيق")
        } catch (e: Exception) {
            onError("حدث خطأ أثناء بدء التسجيل: ${e.message}")
        }
    }

    fun stopRecording(): ByteArray? {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio recorder", e)
        } finally {
            audioRecord = null
        }
        return null
    }

    /**
     * Plays raw PCM audio data directly.
     */
    fun playPcm(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        recordingScope.launch {
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT
                )
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AUDIO_FORMAT)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize.coerceAtLeast(pcmData.size))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(pcmData, 0, pcmData.size)
                audioTrack.play()
            } catch (e: Exception) {
                Log.e(TAG, "Error playing raw PCM", e)
            }
        }
    }

    /**
     * Plays generic audio bytes (like MP3 from Gemini) using Android MediaPlayer.
     */
    fun playAudioBytes(audioBytes: ByteArray, onComplete: () -> Unit = {}) {
        recordingScope.launch(Dispatchers.Main) {
            try {
                val tempFile = File.createTempFile("tts_playback", ".mp3", context.cacheDir)
                tempFile.deleteOnExit()
                FileOutputStream(tempFile).use { fos ->
                    fos.write(audioBytes)
                }

                val mediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    prepare()
                    start()
                    setOnCompletionListener { mp ->
                        mp.release()
                        tempFile.delete()
                        onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio bytes", e)
                onComplete() // fallback
            }
        }
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
