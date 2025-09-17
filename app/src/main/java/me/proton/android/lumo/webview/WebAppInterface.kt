package me.proton.android.lumo.webview

import android.util.Log
import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.update
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.config.LumoConfig

private const val TAG = "WebAppInterface"

class WebAppInterface(private val activity: MainActivity) {
    @JavascriptInterface
    fun showPayment() {
        Log.d(TAG, "showPayment called from JavaScript")
        activity.runOnUiThread {
            Log.d(TAG, "Setting showDialog state to true")
            activity.showPaymentDialog()
        }
    }

    @JavascriptInterface
    fun startVoiceEntry() {
        Log.d(TAG, "startVoiceEntry called from JavaScript")
        activity.runOnUiThread {
            Log.d(TAG, "Dispatching startVoiceEntry to main thread")
            activity.viewModel.onStartVoiceEntryRequested()
        }
    }

    @JavascriptInterface
    fun retryLoad() {
        Log.d(TAG, "retryLoad called from JavaScript (error page)")
        activity.runOnUiThread {
            Log.d(TAG, "Dispatching retryLoad to main thread")
            activity.viewModel.resetNetworkCheckFlag()
            activity.viewModel.performInitialNetworkCheck()
        }
    }

    @JavascriptInterface
    fun onPageTypeChanged(isLumo: Boolean) {
        Log.d(TAG, "Page type changed: isLumo = $isLumo")
        activity.runOnUiThread {
            activity.viewModel.setIsLumoPage(isLumo)
            // Check current URL to determine if back button should show
            val currentUrl = activity.webView?.url ?: ""
            val isAccountPage = LumoConfig.isAccountDomain(currentUrl)
            activity.showBackButton.value = isAccountPage
            Log.d(
                TAG,
                "Back button visibility updated: ${activity.showBackButton.value} (isLumo: $isLumo, isAccountPage: $isAccountPage, url: $currentUrl)"
            )
        }
    }

    @JavascriptInterface
    fun onNavigation(url: String, type: String) {
        Log.d(TAG, "Navigation: $url, type: $type")
        activity.runOnUiThread {
            // Simplified back button logic: only show on account pages
            val isAccountPage = LumoConfig.isAccountDomain(url)
            activity.showBackButton.value = isAccountPage
            Log.d(
                TAG,
                "Back button visibility updated: ${activity.showBackButton.value} (isAccountPage: $isAccountPage)"
            )
        }
    }

    @JavascriptInterface
    fun onLumoContainerVisible() {
        Log.d(TAG, "Lumo container became visible")
        activity.runOnUiThread {
            Log.d(TAG, "Setting hasSeenLumoContainer to true and isLoading to false")
            activity.viewModel.setHasSeenLumoContainer(true)
            activity.viewModel._uiState.update { it.copy(isLoading = false) }
            Log.d(
                TAG,
                "State updated - hasSeenLumoContainer: ${activity.viewModel.uiState.value.hasSeenLumoContainer}, isLoading: ${activity.viewModel.uiState.value.isLoading}"
            )
        }
    }

    @JavascriptInterface
    fun onKeyboardVisibilityChanged(isVisible: Boolean, keyboardHeight: Int) {
        Log.d(
            TAG,
            "Keyboard visibility changed from native: visible=$isVisible, height=${keyboardHeight}px"
        )
        activity.runOnUiThread {
            val jsCall =
                "if (window.onNativeKeyboardChange) { window.onNativeKeyboardChange($isVisible, $keyboardHeight); } else { console.error('❌ window.onNativeKeyboardChange not found!'); }"
            Log.d(TAG, "Sending precise keyboard measurement to JavaScript")
            activity.webView?.evaluateJavascript(jsCall) { result ->
                Log.d(TAG, "JavaScript execution result: $result")
            }
        }
    }

    @JavascriptInterface
    fun postResult(transactionId: String, resultJson: String) {
        Log.d(TAG, "WebAppInterface.postResult received for ID $transactionId: $resultJson")
        activity.postResult(transactionId, resultJson)
    }

    @JavascriptInterface
    fun onBillingUnavailable(message: String) {
        Log.d(TAG, "onBillingUnavailable called: $message")
        activity.runOnUiThread {
            // You can show a toast, update UI, or handle this however makes sense for your app
            android.widget.Toast.makeText(
                activity,
                "Billing is not available on this device",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
} 