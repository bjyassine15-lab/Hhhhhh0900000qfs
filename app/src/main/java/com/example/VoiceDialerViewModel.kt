package com.example

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceDialerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ContactRepository
    private val sharedPrefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Stateflows
    val contacts: StateFlow<List<Contact>>

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isAdminMode = MutableStateFlow(false)
    val isAdminMode: StateFlow<Boolean> = _isAdminMode.asStateFlow()

    private val _sensitivityThreshold = MutableStateFlow(0.35f)
    val sensitivityThreshold: StateFlow<Float> = _sensitivityThreshold.asStateFlow()

    private val _isTtsSpeaking = MutableStateFlow(false)
    val isTtsSpeaking: StateFlow<Boolean> = _isTtsSpeaking.asStateFlow()

    // Registration states
    private val _isRegisterRecording = MutableStateFlow(false)
    val isRegisterRecording: StateFlow<Boolean> = _isRegisterRecording.asStateFlow()

    private val _registeredEmbedding = MutableStateFlow<List<FloatArray>?>(null)
    val registeredEmbedding: StateFlow<List<FloatArray>?> = _registeredEmbedding.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    // Trigger phone call event
    private val _callIntentEvent = MutableSharedFlow<String>()
    val callIntentEvent: SharedFlow<String> = _callIntentEvent.asSharedFlow()

    private val audioRecorder = AudioRecorder(application)
    private var localTts: TextToSpeech? = null
    private var isTtsInitialized = false
    private var onLocalTtsComplete: (() -> Unit)? = null

    init {
        val database = ContactDatabase.getDatabase(application)
        repository = ContactRepository(database.contactDao())
        contacts = repository.allContacts.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Load sensitivity threshold
        val savedThreshold = sharedPrefs.getFloat(KEY_THRESHOLD, 0.35f)
        _sensitivityThreshold.value = savedThreshold

        // Initialize Android TextToSpeech for offline fallback
        initLocalTts(application)
    }

    private fun initLocalTts(context: Context) {
        localTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val arabicResult = localTts?.setLanguage(Locale("ar"))
                if (arabicResult == TextToSpeech.LANG_MISSING_DATA || arabicResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Arabic language is not supported on this device's local TTS engine.")
                }
                isTtsInitialized = true
                setupTtsProgressListener()
            } else {
                Log.e(TAG, "Local TTS initialization failed.")
            }
        }
    }

    private fun setupTtsProgressListener() {
        localTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isTtsSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isTtsSpeaking.value = false
                viewModelScope.launch(Dispatchers.Main) {
                    onLocalTtsComplete?.invoke()
                    onLocalTtsComplete = null
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isTtsSpeaking.value = false
                Log.e(TAG, "Local TTS Error")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _isTtsSpeaking.value = false
                Log.e(TAG, "Local TTS Error code: $errorCode")
            }
        })
    }

    /**
     * Speaks text using Gemini TTS (online, premium) or Android TTS (offline fallback)
     */
    fun speak(text: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _isTtsSpeaking.value = true
            // Attempt Gemini TTS (Premium Voice)
            val audioBytes = withContext(Dispatchers.IO) {
                GeminiTtsService.generateSpeech(text)
            }

            if (audioBytes != null) {
                // Play generated voice
                audioRecorder.playAudioBytes(audioBytes) {
                    _isTtsSpeaking.value = false
                    onComplete()
                }
            } else {
                // Offline Fallback using Android TTS
                if (isTtsInitialized) {
                    onLocalTtsComplete = onComplete
                    val params = android.os.Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "offline_tts")
                    }
                    localTts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "offline_tts")
                } else {
                    Log.e(TAG, "Both Gemini and Local TTS are unavailable.")
                    _isTtsSpeaking.value = false
                    onComplete()
                }
            }
        }
    }

    /**
     * Sets sensitivity threshold and persists to SharedPreferences.
     */
    fun updateSensitivityThreshold(value: Float) {
        _sensitivityThreshold.value = value
        sharedPrefs.edit().putFloat(KEY_THRESHOLD, value).apply()
    }

    fun setAdminMode(enabled: Boolean) {
        _isAdminMode.value = enabled
    }

    /**
     * Toggles recording for main screen audio voice matching.
     */
    fun toggleVoiceMatchRecording() {
        if (_isRecording.value) {
            // Stop recording & process
            _isRecording.value = false
            audioRecorder.stopRecording()
        } else {
            // Start recording
            _isRecording.value = true
            audioRecorder.startRecording(
                onDataAvailable = { pcmBytes ->
                    processVoiceMatching(pcmBytes)
                },
                onError = { err ->
                    _isRecording.value = false
                    viewModelScope.launch { _errorMessage.emit(err) }
                }
            )
        }
    }

    /**
     * Runs DTW (Dynamic Time Warping) voice matching algorithm over all contacts.
     */
    private fun processVoiceMatching(pcmBytes: ByteArray) {
        viewModelScope.launch {
            // Extract MFCC feature sequence from active speech
            val liveSequence = withContext(Dispatchers.Default) {
                AudioFeatureExtractor.extractMfccSequence(pcmBytes)
            }

            if (liveSequence.isEmpty()) {
                val failureMessage = "لم يتم اكتشاف صوت، حاولي مرة أخرى"
                speak(failureMessage)
                return@launch
            }

            val contactList = contacts.value
            val currentThreshold = _sensitivityThreshold.value

            var bestContact: Contact? = null
            var minDistance = Float.MAX_VALUE

            // 1. Loop through all contacts and calculate DTW distance (lower distance is better)
            for (contact in contactList) {
                val contactSequence = contact.getMfccSequence()
                if (contactSequence.isEmpty()) continue

                val distance = AudioFeatureExtractor.computeDtwDistance(liveSequence, contactSequence)
                Log.d(TAG, "Contact: ${contact.name}, DTW Distance: $distance")

                // Keep track of the minimum DTW Distance (closest match)
                if (distance < minDistance) {
                    minDistance = distance
                    bestContact = contact
                }
            }

            // 2. Acceptance check against sensitivity threshold
            if (bestContact != null && minDistance <= currentThreshold) {
                // Successful match: announce name clearly and initiate call
                val announcement = "الاتصال بـ ${bestContact.name}"
                speak(announcement) {
                    viewModelScope.launch {
                        _callIntentEvent.emit(bestContact.phoneNumber)
                    }
                }
            } else {
                // Rejected match (distance too high or no contacts): trigger audio/visual error feedback
                val failureMessage = "لم أتعرف على الاسم، حاولي مرة أخرى"
                speak(failureMessage)
            }
        }
    }

    /**
     * Toggles recording for voice template registration in Admin panel.
     */
    fun toggleRegisterRecording() {
        if (_isRegisterRecording.value) {
            _isRegisterRecording.value = false
            audioRecorder.stopRecording()
        } else {
            _isRegisterRecording.value = true
            _registeredEmbedding.value = null
            audioRecorder.startRecording(
                onDataAvailable = { pcmBytes ->
                    viewModelScope.launch {
                        val sequence = withContext(Dispatchers.Default) {
                            AudioFeatureExtractor.extractMfccSequence(pcmBytes)
                        }
                        _registeredEmbedding.value = sequence
                        // Playback the recorded voice to verify!
                        audioRecorder.playPcm(pcmBytes)
                    }
                },
                onError = { err ->
                    _isRegisterRecording.value = false
                    viewModelScope.launch { _errorMessage.emit(err) }
                }
            )
        }
    }

    fun playContactVoice(contact: Contact) {
        speak(contact.name)
    }

    /**
     * Adds a new contact or updates if id exists.
     */
    fun saveContact(
        id: Int = 0,
        name: String,
        phone: String,
        photoUri: Uri?,
        embedding: List<FloatArray>
    ) {
        viewModelScope.launch {
            val embeddingStr = Contact.serializeMfccSequence(embedding)
            val contact = Contact(
                id = id,
                name = name,
                phoneNumber = phone,
                photoUri = photoUri?.toString(),
                audioEmbedding = embeddingStr
            )
            if (id == 0) {
                repository.insert(contact)
            } else {
                repository.update(contact)
            }
            // Reset registration
            _registeredEmbedding.value = null
        }
    }

    fun deleteContact(contact: Contact) {
        viewModelScope.launch {
            repository.delete(contact)
        }
    }

    override fun onCleared() {
        super.onCleared()
        localTts?.stop()
        localTts?.shutdown()
    }

    companion object {
        private const val TAG = "VoiceDialerViewModel"
        private const val PREFS_NAME = "voice_dialer_prefs"
        private const val KEY_THRESHOLD = "similarity_threshold"
    }
}
