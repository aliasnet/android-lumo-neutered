package me.proton.android.lumo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.material3.Scaffold
import android.Manifest
import android.annotation.SuppressLint
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import me.proton.android.lumo.ui.components.PaymentDialog
import me.proton.android.lumo.ui.theme.LumoTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.rememberCoroutineScope
import me.proton.android.lumo.ui.components.SpeechInputSheetContent
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.activity.viewModels
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import me.proton.android.lumo.ui.components.LoadingScreen
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.proton.android.lumo.models.Feature
import me.proton.android.lumo.speech.SpeechRecognitionManager
import me.proton.android.lumo.webview.WebViewScreen
import me.proton.android.lumo.ui.theme.Purple
import me.proton.android.lumo.config.LumoConfig
import me.proton.android.lumo.managers.BillingManagerWrapper
import me.proton.android.lumo.managers.WebViewManager
import me.proton.android.lumo.managers.PermissionManager
import me.proton.android.lumo.managers.UIManager
import me.proton.android.lumo.interfaces.WebViewProvider
import android.widget.Toast
import me.proton.android.lumo.di.DependencyProvider

private const val TAG = "MainActivity"


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity(), WebViewProvider {
    // Make viewModel accessible to WebAppInterface
    internal val viewModel: MainActivityViewModel by viewModels()
    
    // Manager instances for separation of concerns
    private lateinit var billingManagerWrapper: BillingManagerWrapper
    private lateinit var webViewManager: WebViewManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var uiManager: UIManager
    
    // UI state
    private val showDialog = mutableStateOf(false)
    internal var showBackButton = mutableStateOf(false)
    internal var isLoading = mutableStateOf(false)
    internal var hasSeenLumoContainer = mutableStateOf(false)

    // Speech Recognition
    private lateinit var speechRecognitionManager: SpeechRecognitionManager

    private val _lottieComposition = MutableStateFlow<LottieComposition?>(null)
    private val lottieComposition: StateFlow<LottieComposition?> = _lottieComposition.asStateFlow()
    
    // Expose WebView for backward compatibility with existing code
    var webView: android.webkit.WebView?
        get() = webViewManager.webView
        set(value) { if (value != null) webViewManager.setWebView(value) }
    
    // Expose file path callback for backward compatibility
    var filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?
        get() = webViewManager.filePathCallback
        set(value) { webViewManager.filePathCallback = value }

    // JavaScript result callback handler
    @JavascriptInterface
    fun postResult(transactionId: String, resultJson: String) {
        Log.d(TAG, "MainActivity.postResult received for ID $transactionId: $resultJson")
        
        // Delegate to billing manager wrapper
        runOnUiThread {
            billingManagerWrapper.handleJavaScriptResult(transactionId, resultJson)
        }
    }

    // Expose file chooser launcher for backward compatibility
    val fileChooserLauncher get() = permissionManager.fileChooserLauncher

    @SuppressLint("StateFlowValueCalledInComposition")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize managers
        initializeManagers()

        Log.d(TAG, "onCreate called")
        Log.d(TAG, LumoConfig.getConfigInfo())

        LottieCompositionFactory.fromAsset(this, "lumo-loader.json").addListener { composition ->
            _lottieComposition.value = composition
        }

        // Initialize speech recognition manager
        speechRecognitionManager = SpeechRecognitionManager(this)

        // Reset container visibility state on app start
        hasSeenLumoContainer.value = false
        isLoading.value = true

        ServiceWorkerController.getInstance()
            .setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    return null
                }
            })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collectLatest { event ->
                    when (event) {
                        is UiEvent.EvaluateJavascript -> {
                            Log.d(TAG, "Received EvaluateJavascript event")
                            webView?.evaluateJavascript(event.script) { result ->
                                viewModel.handleJavascriptResult(result)
                            }
                        }

                        is UiEvent.ShowToast -> {
                            Log.d(TAG, "Received ShowToast event: ${event.message}")
                            Toast.makeText(this@MainActivity, event.message, Toast.LENGTH_LONG)
                                .show()
                        }

                        is UiEvent.RequestAudioPermission -> {
                            Log.d(TAG, "Received RequestAudioPermission event")
                            permissionManager.requestRecordAudioPermission()
                        }
                    }
                }
            }
        }

        // Trigger the initial network connectivity check (independent of billing)
        viewModel.performInitialNetworkCheck()

        // Add a global safety timer to ensure loading screen doesn't get stuck
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Global safety timeout reached for loading screen")
            if (isLoading.value) {
                Log.d(TAG, "Forcing loading screen to hide from global timer")
                isLoading.value = false
                hasSeenLumoContainer.value = true
            }
        }, 8000) // 8 second global timeout

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val initialUrl by viewModel.initialUrl.collectAsStateWithLifecycle()

            LumoTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                val scope = rememberCoroutineScope()

                LaunchedEffect(uiState.showSpeechSheet) {
                    Log.d(
                        TAG,
                        "LaunchedEffect(showSpeechSheet) triggered. showSpeechSheet = ${uiState.showSpeechSheet}"
                    )
                    scope.launch {
                        if (uiState.showSpeechSheet) {
                            Log.d(
                                TAG,
                                "Effect: showSpeechSheet is TRUE. Calling sheetState.show()..."
                            )
                            try {
                                sheetState.show()
                                Log.d(TAG, "Effect: sheetState.show() finished.")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error showing bottom sheet", e)
                            }
                        } else {
                            Log.d(
                                TAG,
                                "Effect: showSpeechSheet is FALSE. Checking if sheet is visible..."
                            )
                            if (sheetState.isVisible) {
                                Log.d(TAG, "Effect: Sheet is visible. Calling sheetState.hide()...")
                                try {
                                    sheetState.hide()
                                    Log.d(TAG, "Effect: sheetState.hide() finished.")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error hiding bottom sheet", e)
                                }
                            } else {
                                Log.d(TAG, "Effect: Sheet is already hidden.")
                            }
                        }
                    }
                }

                if (uiState.showSpeechSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { viewModel.onCancelListening() },
                        sheetState = sheetState,
                        containerColor = Purple,
                    ) {
                        SpeechInputSheetContent(
                            isListening = uiState.isListening,
                            partialSpokenText = uiState.partialSpokenText,
                            rmsDbValue = uiState.rmsDbValue,
                            speechStatusText = uiState.speechStatusText,
                            onCancel = { viewModel.onCancelListening() },
                            onSubmit = { viewModel.onSubmitTranscription() }
                        )
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        if (showBackButton.value) {
                            TopAppBar(
                                title = { Text(stringResource(id = R.string.back_to_lumo)) },
                                navigationIcon = {
                                    IconButton(onClick = {
                                        // Simplified back logic: always go to Lumo URL
                                        Log.d(TAG, "Back button clicked, navigating to Lumo")
                                        webView?.loadUrl(LumoConfig.LUMO_URL)
                                    }) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.lumo_icon),
                                            contentDescription = stringResource(id = R.string.back_to_lumo),
                                            tint = Color.Unspecified,
                                            modifier = Modifier.height(25.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Ensure navigation stack is initialized with initialUrl
                        if (initialUrl != null && viewModel.navigationStack.value.isEmpty()) {
                            viewModel.onNavigation(initialUrl!!, "push")
                        }
                        // Always show WebViewScreen if initialUrl is not null
                        if (initialUrl != null) {
                            WebViewScreen(
                                activity = this@MainActivity,
                                initialUrl = initialUrl!!, // Pass the determined URL
                                onWebViewCreated = { createdWebView ->
                                    webViewManager.setWebView(createdWebView)
                                    Log.d(
                                        TAG,
                                        "WebView created and stored in WebViewManager."
                                    )
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        if (uiState.isLoading) {
                                            Log.d(
                                                TAG,
                                                "WebView created but still loading, forcing state update"
                                            )
                                            viewModel.viewModelScope.launch {
                                                viewModel._uiState.update {
                                                    it.copy(
                                                        isLoading = false,
                                                        hasSeenLumoContainer = true
                                                    )
                                                }
                                            }
                                        }
                                    }, 3000)
                                }
                            )
                        }
                        // Overlay LoadingScreen if loading (use only ViewModel state)
                        if (uiState.isLoading && !uiState.hasSeenLumoContainer && uiState.isLumoPage) {
                            Log.d(
                                TAG,
                                "Overlaying loading screen - isLoading: ${uiState.isLoading}, hasSeenLumoContainer: ${uiState.hasSeenLumoContainer}, isLumoPage: ${uiState.isLumoPage}"
                            )
                            LoadingScreen(lottieComposition.collectAsStateWithLifecycle().value)
                        }
                        if (initialUrl != null) {
                            Log.d(TAG, "Showing, or trying to show PaymentDialog. ")
                            billingManagerWrapper.getBillingManager()?.let { manager ->
                                PaymentDialog(showDialog, manager)
                            } ?: run {
                                // When billing is unavailable, show a simple dialog informing the user
                                if (showDialog.value) {
                                    billingManagerWrapper.showBillingUnavailableDialog(webView)
                                    showDialog.value = false // Close the dialog request
                                }
                            }
                        }
                        if (initialUrl == null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text("Error determining initial URL.")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiManager.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognitionManager.destroy() // Release the recognizer
        webViewManager.destroy()
    }

    // Make this accessible to WebAppInterface
    fun showPaymentDialog() {
        if (billingManagerWrapper.getBillingManager() != null) {
            showDialog.value = true
        } else {
            Log.w(TAG, "Payment dialog requested but billing is unavailable")
            billingManagerWrapper.showBillingUnavailableDialog(webView)
        }
    }
    
    // Getter for BillingManager
    fun getBillingManager() = billingManagerWrapper.getBillingManager()

    // Convenience methods that delegate to BillingManagerWrapper
    fun sendPaymentTokenToWebView(
        webView: android.webkit.WebView,
        payload: me.proton.android.lumo.models.PaymentTokenPayload,
        callback: ((Result<me.proton.android.lumo.models.PaymentJsResponse>) -> Unit)? = null
    ) {
        billingManagerWrapper.sendPaymentTokenToWebView(webView, payload, callback)
    }

    fun sendSubscriptionEventToWebView(
        webView: android.webkit.WebView,
        payload: me.proton.android.lumo.models.Subscription,
        callback: ((Result<me.proton.android.lumo.models.PaymentJsResponse>) -> Unit)? = null
    ) {
        billingManagerWrapper.sendSubscriptionEventToWebView(webView, payload, callback)
    }

    fun getPlansFromWebView(
        webView: android.webkit.WebView,
        callback: ((Result<me.proton.android.lumo.models.PaymentJsResponse>) -> Unit)? = null
    ) {
        billingManagerWrapper.getPlansFromWebView(webView, callback)
    }

    fun getSubscriptionsFromWebView(
        webView: android.webkit.WebView,
        callback: ((Result<me.proton.android.lumo.models.PaymentJsResponse>) -> Unit)? = null
    ) {
        billingManagerWrapper.getSubscriptionsFromWebView(webView, callback)
    }
    
    // WebViewProvider interface implementation
    override fun getCurrentWebView(): android.webkit.WebView? = webView
    
    override fun getPlansFromWebView(callback: (Result<me.proton.android.lumo.models.PaymentJsResponse>) -> Unit) {
        webView?.let { webView ->
            getPlansFromWebView(webView, callback)
        } ?: callback(Result.failure(Exception("WebView not available")))
    }
    
    override fun getSubscriptionsFromWebView(callback: (Result<me.proton.android.lumo.models.PaymentJsResponse>) -> Unit) {
        webView?.let { webView ->
            getSubscriptionsFromWebView(webView, callback)
        } ?: callback(Result.failure(Exception("WebView not available")))
    }

     override fun onConfigurationChanged(newConfig: Configuration) {
         super.onConfigurationChanged(newConfig)
         uiManager.onConfigurationChanged(newConfig)
     }

    /**
     * Initialize all manager instances
     */
    private fun initializeManagers() {
        // Initialize UI manager first to set up edge-to-edge and status bar
        uiManager = UIManager(this)
        uiManager.initializeUI()
        
        // Initialize WebView manager first
        webViewManager = WebViewManager(this)
        
        // Initialize permission manager with callback for permission results and WebView manager
        permissionManager = PermissionManager(this, { permission, isGranted ->
            handlePermissionResult(permission, isGranted)
        }, webViewManager)
        
        // Initialize dependency provider
        DependencyProvider.initialize(this)
        
        // Get BillingManagerWrapper from dependency provider
        billingManagerWrapper = DependencyProvider.getBillingManagerWrapper(this)
        billingManagerWrapper.initializeBilling()
        
        Log.d(TAG, "All managers initialized successfully")
    }
    
    /**
     * Handle permission results from PermissionManager
     */
    private fun handlePermissionResult(permission: String, isGranted: Boolean) {
        when (permission) {
            Manifest.permission.RECORD_AUDIO -> {
                viewModel.updatePermissionStatus() // Update ViewModel's knowledge regardless
                if (isGranted) {
                    Log.d(TAG, "RECORD_AUDIO permission granted by user, re-triggering voice entry request")
                    viewModel.onStartVoiceEntryRequested()
                } else {
                    Log.w(TAG, "RECORD_AUDIO permission denied by user")
                    viewModel.viewModelScope.launch {
                        viewModel._eventChannel.send(UiEvent.ShowToast(getString(R.string.permission_mic_rationale)))
                    }
                }
            }
        }
    }

    companion object {
        private val features = listOf(
            Feature("Daily chats", false, true),
            Feature("Chat history", false, true),
            Feature("Starred chats", false, true),
            Feature("Large uploads", false, true),
            Feature("Priority access at peak times", false, true),
            Feature("Priority customer support", false, true)
        )
    }
}