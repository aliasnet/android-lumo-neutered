package me.proton.android.lumo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.android.lumo.R
import me.proton.android.lumo.config.LumoConfig
import me.proton.android.lumo.domain.WebEvent
import me.proton.android.lumo.speech.SpeechRecognitionManager
import me.proton.android.lumo.utils.isHostReachable
import me.proton.android.lumo.webview.keyboardHeightChange

private const val TAG = "MainActivityViewModel"

// Define UI State (can be expanded later)
data class MainUiState(
    val showPaymentDialog: Boolean = false,
    val billingAvailable: Boolean = false,
    val showSpeechSheet: Boolean = false,
    val isListening: Boolean = false,
    val partialSpokenText: String = "",
    val rmsDbValue: Float = 0f,
    val speechStatusText: String = "",
    val hasRecordAudioPermission: Boolean = false,
    val isLoading: Boolean = true,
    val initialLoadError: String? = null,
    val isLumoPage: Boolean = true,
    val hasSeenLumoContainer: Boolean = false,
    val shouldShowBackButton: Boolean = false,
)

// Define Events for communication (e.g., JS evaluation)
sealed class UiEvent {
    data class EvaluateJavascript(val script: String) : UiEvent()
    data class ShowToast(val message: String) : UiEvent()
    object RequestAudioPermission : UiEvent()
    data class ForwardBillingResult(val transactionId: String, val resultJson: String) : UiEvent()
}

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    internal val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    internal val _eventChannel = Channel<UiEvent>()
    val events = _eventChannel.receiveAsFlow()

    private val speechRecognitionManager = SpeechRecognitionManager(application)

    // State for initial URL after network check
    private val _initialUrl =
        MutableStateFlow<String?>(LumoConfig.LUMO_URL) // Start with default URL
    val initialUrl: StateFlow<String?> = _initialUrl.asStateFlow()
    private var checkCompleted = false // Prevent re-checking on config change

    private val _webEvents = MutableSharedFlow<WebEvent>(extraBufferCapacity = 64)

    init {
        setupSpeechRecognition()
        updatePermissionStatus()
        determineSpeechStatusText()

        viewModelScope.launch {
            _webEvents.collect { event ->
                when (event) {
                    // UI state toggle; Activity will render from state in a later step
                    WebEvent.ShowPaymentRequested -> {
                        val isBillingAvailable = _uiState.value.billingAvailable
                        Log.d(TAG, "ShowPaymentRequested received. Billing available: $isBillingAvailable")
                        if (!isBillingAvailable) {
                            val message =
                                getApplication<Application>().getString(R.string.billing_unavailable_generic)
                            _eventChannel.trySend(UiEvent.ShowToast(message))
                        }
                        _uiState.update { it.copy(showPaymentDialog = true) }
                    }

                    WebEvent.StartVoiceEntryRequested -> {
                        onStartVoiceEntryRequested()
                    }

                    WebEvent.RetryLoadRequested -> {
                        resetNetworkCheckFlag()
                        performInitialNetworkCheck()
                    }

                    is WebEvent.PageTypeChanged -> {
                        _uiState.update { state ->
                            val newIsLumo = event.isLumo
                            val showBack = LumoConfig.isAccountDomain(event.url)
                            state.copy(
                                isLumoPage = newIsLumo,
                                shouldShowBackButton = showBack,
                            )
                        }
                    }

                    is WebEvent.Navigated -> {
                        _uiState.update { state ->
                            val showBack = LumoConfig.isAccountDomain(event.url)
                            state.copy(
                                shouldShowBackButton = showBack
                            )
                        }
                    }

                    WebEvent.LumoContainerVisible -> {
                        setHasSeenLumoContainer(true)
                        _uiState.update { it.copy(isLoading = false) }
                    }

                    is WebEvent.KeyboardVisibilityChanged -> {
                        _eventChannel.trySend(
                            UiEvent.EvaluateJavascript(
                                keyboardHeightChange(
                                    event.isVisible,
                                    event.keyboardHeightPx
                                )
                            )
                        )
                    }

                    is WebEvent.PostResult -> {
                        // We’ll properly forward this to billing via a UiEvent in Step 4.
                        // For now, just confirm we received it.
                        _eventChannel.trySend(
                            UiEvent.ForwardBillingResult(event.transactionId, event.resultJson)
                        )
                    }
                }
            }
        }
        // Don't call performInitialNetworkCheck here, call from Activity onCreate
    }

    fun onWebEvent(event: WebEvent) {
        _webEvents.tryEmit(event)
    }

    fun dismissPaymentDialog() {
        _uiState.update { it.copy(showPaymentDialog = false) }
    }

    fun setBillingAvailability(isAvailable: Boolean) {
        if (_uiState.value.billingAvailable == isAvailable) return
        Log.d(TAG, "Billing availability changed: $isAvailable")
        _uiState.update { it.copy(billingAvailable = isAvailable) }
    }

    // --- Initial Network Check --- 
    fun performInitialNetworkCheck() {
        if (checkCompleted) {
            Log.d(TAG, "Initial network check already completed, skipping.")
            return
        }
        _uiState.update { it.copy(isLoading = true, initialLoadError = null) } // Show loading

        // Add safety timeout to ensure loading state is cleared even if network check takes too long
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000) // 5 second timeout
            if (_uiState.value.isLoading) {
                Log.d(TAG, "Network check taking too long, forcing loading state off")
                // Ensure we have a valid URL (should already be set to default)
                if (_initialUrl.value == null) {
                    _initialUrl.value = LumoConfig.LUMO_URL
                    Log.d(TAG, "Setting fallback URL: ${_initialUrl.value}")
                }
                _uiState.update { it.copy(isLoading = false) }
            }
        }

        viewModelScope.launch {
            val host = LumoConfig.LUMO_DOMAIN
            val port = 443
            val timeout = 3000 // 3 seconds
            Log.d(TAG, "Performing initial network check for $host:$port...")

            val reachable = isHostReachable(host, port, timeout) // Call the suspend function

            if (reachable) {
                Log.d(TAG, "Initial network check: Host $host is reachable.")
                _initialUrl.value = LumoConfig.LUMO_URL
                _uiState.update { it.copy(initialLoadError = null) }
            } else {
                Log.w(TAG, "Initial network check: Host $host is NOT reachable within $timeout ms.")
                _initialUrl.value = "file:///android_asset/network_error.html"
                _uiState.update { it.copy(initialLoadError = "Host not reachable") } // Set error state
            }
            _uiState.update { it.copy(isLoading = false) } // Hide loading
            checkCompleted = true
            Log.d(TAG, "Initial network check finished. Initial URL set to: ${_initialUrl.value}")
        }
    }

    fun resetNetworkCheckFlag() {
        Log.d(TAG, "Resetting checkCompleted flag for retry.")
        checkCompleted = false
    }

    private fun setupSpeechRecognition() {
        speechRecognitionManager.setListener(object :
            SpeechRecognitionManager.SpeechRecognitionListener {
            override fun onReadyForSpeech() {
                _uiState.update { it.copy(isListening = true) }
            }

            override fun onBeginningOfSpeech() {
                // Nothing to do here
            }

            override fun onRmsChanged(rmsdB: Float) {
                _uiState.update { it.copy(rmsDbValue = rmsdB) }
            }

            override fun onEndOfSpeech() {
                _uiState.update { it.copy(isListening = false) }
            }

            override fun onError(errorMessage: String) {
                _uiState.update { it.copy(isListening = false, showSpeechSheet = false) }
                viewModelScope.launch {
                    _eventChannel.send(UiEvent.ShowToast(errorMessage))
                }
            }

            override fun onPartialResults(text: String) {
                _uiState.update { it.copy(partialSpokenText = text) }
            }

            override fun onResults(text: String) {
                _uiState.update { it.copy(partialSpokenText = text, isListening = false) }
            }
        })
    }

    fun updatePermissionStatus() {
        val hasPermission = speechRecognitionManager.isPermissionGranted()
        _uiState.value = _uiState.value.copy(hasRecordAudioPermission = hasPermission)
        Log.d(TAG, "Record audio permission status updated: $hasPermission")
    }

    private fun determineSpeechStatusText() {
        val context = getApplication<Application>()
        val statusText = if (speechRecognitionManager.isOnDeviceRecognitionAvailable()) {
            Log.d(TAG, "On-device recognition IS available.")
            context.getString(R.string.speech_status_on_device)
        } else {
            Log.d(TAG, "On-device recognition NOT available.")
            context.getString(R.string.speech_status_network)
        }
        _uiState.value = _uiState.value.copy(speechStatusText = statusText)
    }

    // --- Event Handlers ---

    fun onStartVoiceEntryRequested() {
        Log.d(TAG, "onStartVoiceEntryRequested")
        if (speechRecognitionManager.isPermissionGranted()) {
            if (!speechRecognitionManager.isSpeechRecognitionAvailable()) {
                viewModelScope.launch {
                    _eventChannel.send(UiEvent.ShowToast(getApplication<Application>().getString(R.string.speech_not_available)))
                }
                return
            }
            _uiState.value = _uiState.value.copy(showSpeechSheet = true)
            speechRecognitionManager.startListening()
        } else {
            Log.d(TAG, "Permission not granted. Requesting permission via event.")
            viewModelScope.launch { _eventChannel.send(UiEvent.RequestAudioPermission) }
        }
    }

    fun onCancelListening() {
        Log.d(TAG, "onCancelListening")
        speechRecognitionManager.cancelListening()
        _uiState.value = _uiState.value.copy(
            isListening = false,
            showSpeechSheet = false,
            partialSpokenText = ""
        )
    }

    fun onSubmitTranscription() {
        val transcript = _uiState.value.partialSpokenText
        Log.d(TAG, "onSubmitTranscription: $transcript")

        // Reset state immediately
        _uiState.value = _uiState.value.copy(isListening = false, showSpeechSheet = false)
        speechRecognitionManager.cancelListening()

        if (transcript.isNotEmpty()) {
            val escaped = transcript
                .replace("\\", "\\\\") // Must replace backslash first!
                .replace("\"", "\\\"") // Escape double quotes
                .replace("'", "\\'")   // Escape single quotes (optional but safe)
                .replace("\n", "\\n")  // Escape newlines
                .replace("\r", "\\r")  // Escape carriage returns
            val escapedText = "\"$escaped\""

            val script = """
                (function() {
                    if (typeof window.insertPromptAndSubmit === 'function') {
                        return window.insertPromptAndSubmit($escapedText);
                    } else {
                        console.error('insertPromptAndSubmit function not found');
                        return 'Error: insertPromptAndSubmit not found';
                    }
                })()
            """.trimIndent()

            Log.d(TAG, "Executing script: $script")
            viewModelScope.launch {
                _eventChannel.send(UiEvent.EvaluateJavascript(script))
            }
        } else {
            Log.w(TAG, "Skipping submission, empty transcript")
        }
        // Clear partial text after attempting submission
        _uiState.value = _uiState.value.copy(partialSpokenText = "")
    }

    fun handleJavascriptResult(result: String?) {
        Log.d(TAG, "JavaScript execution result: $result")
        if (result == null || result == "null" || result.contains("Error")) {
            Log.e(TAG, "JavaScript execution failed or function not found. Result: $result")
            viewModelScope.launch {
                _eventChannel.send(UiEvent.ShowToast(getApplication<Application>().getString(R.string.submit_prompt_failed)))
            }
        } else {
            Log.d(TAG, "JavaScript insertPromptAndSubmit executed successfully.")
        }
    }

    fun setIsLumoPage(isLumo: Boolean) {
        _uiState.update { it.copy(isLumoPage = isLumo) }
    }

    fun setHasSeenLumoContainer(seen: Boolean) {
        _uiState.update { it.copy(hasSeenLumoContainer = seen) }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognitionManager.removeListener()
        speechRecognitionManager.destroy()
    }
}
