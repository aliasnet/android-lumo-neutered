package me.proton.android.lumo.managers

import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.billing.gateway.BillingGateway
import me.proton.android.lumo.billing.gateway.BillingProvider
import me.proton.android.lumo.billing.gateway.NoopBillingGateway
import me.proton.android.lumo.models.PaymentJsResponse
import me.proton.android.lumo.models.PaymentTokenPayload
import me.proton.android.lumo.models.Subscription
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BillingManagerWrapper"

/**
 * Wrapper class that handles all billing-related operations and Google Play Services integration.
 * Provides a clean interface for MainActivity while handling billing availability gracefully.
 */
class BillingManagerWrapper(private val activity: MainActivity) {

    interface BillingCallbacks {
        fun sendPaymentTokenToWebView(
            payload: PaymentTokenPayload,
            callback: ((Result<PaymentJsResponse>) -> Unit)? = null
        )

        fun sendSubscriptionEventToWebView(
            payload: Subscription,
            callback: ((Result<PaymentJsResponse>) -> Unit)? = null
        )
    }

    private val _billingGateway = MutableStateFlow<BillingGateway>(NoopBillingGateway())
    val billingGatewayFlow: StateFlow<BillingGateway> = _billingGateway.asStateFlow()

    private val billingCallbacks = object : BillingCallbacks {
        override fun sendPaymentTokenToWebView(
            payload: PaymentTokenPayload,
            callback: ((Result<PaymentJsResponse>) -> Unit)?
        ) {
            activity.webView?.let {
                sendPaymentTokenToWebView(it, payload, callback)
            }
        }

        override fun sendSubscriptionEventToWebView(
            payload: Subscription,
            callback: ((Result<PaymentJsResponse>) -> Unit)?
        ) {
            activity.webView?.let {
                sendSubscriptionEventToWebView(it, payload, callback)
            }
        }
    }

    // Map to store callbacks for JS results
    private val pendingJsCallbacks =
        ConcurrentHashMap<String, (Result<PaymentJsResponse>) -> Unit>()

    /**
     * Enum defining the types of JavaScript functions to invoke in the WebView
     */
    enum class PAYMENT_REQUEST_TYPE(val functionName: String) {
        PAYMENT_TOKEN("postPaymentToken"),
        SUBSCRIPTION("postSubscription"),
        GET_PLANS("getPlans"),
        GET_SUBSCRIPTIONS("getSubscriptions")
    }

    /**
     * Initialize billing with comprehensive Google Services availability check
     */
    fun initializeBilling() {
        activity.lifecycleScope.launch {
            val gateway = BillingProvider.get(activity, billingCallbacks)
            if (gateway.available) {
                Log.d(TAG, "✅ Billing gateway initialized successfully")
            } else {
                Log.i(TAG, "Billing gateway unavailable; using no-op implementation")
            }
            _billingGateway.value = gateway
        }
    }

    fun getBillingGateway(): BillingGateway = _billingGateway.value

    /**
     * Generic method to send data to the WebView's JavaScript API using JavascriptInterface for callback
     */
    private fun <T> sendDataToWebView(
        webView: WebView,
        payload: T?, // Payload is nullable
        jsFunction: PAYMENT_REQUEST_TYPE,
        callback: ((Result<PaymentJsResponse>) -> Unit)? = null
    ) {
        val gson = Gson()
        val payloadJson = payload?.let { gson.toJson(it) } ?: "null"
        val payloadLog = payload?.let { gson.toJson(it) } ?: "No payload"

        // Generate a unique ID for this transaction
        val transactionId = UUID.randomUUID().toString()

        // Store the callback if it exists
        if (callback != null) {
            pendingJsCallbacks[transactionId] = callback
        }

        Log.d(TAG, "Sending ${jsFunction.name.lowercase()} (ID: $transactionId)...")
        Log.d(TAG, "Payload: $payloadLog")

        val jsFunctionCall =
            if (jsFunction == PAYMENT_REQUEST_TYPE.GET_PLANS || jsFunction == PAYMENT_REQUEST_TYPE.GET_SUBSCRIPTIONS) {
                "window.paymentApiInstance.${jsFunction.functionName}('android')"
            } else {
                "window.paymentApiInstance.${jsFunction.functionName}($payloadJson)"
            }

        // JS now calls AndroidInterface.postResult instead of returning a value
        val js = """
            (async function() {
                const txId = '$transactionId'; // Pass transactionId to JS
                try {
                    // Check if the Android interface exists before using it
                    if (typeof Android === 'undefined' || typeof Android.postResult !== 'function') {
                         console.error('Android.postResult is not available. Cannot send result back.');
                         // If no callback was provided originally, this is fine. If one was, we can't fulfill it.
                         return; // Exit early
                    }

                    if (window.paymentApiInstance && typeof window.paymentApiInstance.${jsFunction.functionName} === 'function') {
                        const result = await $jsFunctionCall;
                        const resultJson = JSON.stringify({ status: 'success', data: result });
                        Android.postResult(txId, resultJson);
                    } else {
                        const errorMsg = 'paymentApiInstance or ${jsFunction.functionName} not found';
                        console.error(errorMsg);
                        const errorJson = JSON.stringify({ status: 'error', message: errorMsg });
                        Android.postResult(txId, errorJson);
                    }
                } catch (e) {
                    const errorMessage = e instanceof Error ? e.message : String(e);
                    console.error('Error executing ${jsFunction.functionName}:', errorMessage);
                    const errorJson = JSON.stringify({ status: 'error', message: 'JS Error: ' + errorMessage });
                    // Ensure we still call back even on error
                    if (typeof Android !== 'undefined' && typeof Android.postResult === 'function') {
                         Android.postResult(txId, errorJson);
                    } else {
                         console.error('Android interface not available to report JS error.');
                    }
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /**
     * Handle JavaScript result callback from MainActivity
     */
    fun handleJavaScriptResult(transactionId: String, resultJson: String): Boolean {
        Log.d(TAG, "handleJavaScriptResult received for ID $transactionId: $resultJson")

        // Retrieve and remove the original callback
        val callback = pendingJsCallbacks.remove(transactionId)
        if (callback == null) {
            Log.e(TAG, "No callback found for transaction ID: $transactionId")
            return false
        }

        // Process the result string and invoke the callback
        val finalResult = processJavascriptResult(resultJson, Gson())
        callback(finalResult)
        return true
    }

    /**
     * Helper function to process JavaScript results
     */
    private fun processJavascriptResult(
        resultString: String?,
        gson: Gson
    ): Result<PaymentJsResponse> {
        // Add check for common unresolved promise representations
        if (resultString?.startsWith("[object Promise]") == true || resultString == "undefined" || resultString == "{}") {
            Log.w(
                TAG,
                "JavaScript returned a Promise object representation, empty object, or undefined. Raw: $resultString"
            )
            return Result.failure(Exception("JavaScript promise handling error or unexpected result."))
        }
        return try {
            var processableString = resultString?.removeSurrounding("\"")
            // If the string still looks like an encoded JSON string (starts with \" and ends with \")
            // attempt to parse it as a string first to decode it
            if (processableString?.startsWith("\\\"") == true && processableString.endsWith("\\\"")) {
                try {
                    processableString = gson.fromJson(processableString, String::class.java)
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Could not decode potentially double-encoded JSON string: $processableString",
                        e
                    )
                    // Proceed with the original string if decoding fails
                }
            }
            if (processableString != null) {
                val parsedResponse = gson.fromJson(processableString, PaymentJsResponse::class.java)
                if (parsedResponse.status == "success") {
                    Result.success(parsedResponse)
                } else {
                    Result.failure(Exception(parsedResponse.message ?: "Unknown error from JS"))
                }
            } else {
                Result.failure(Exception("JavaScript returned null"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing JS response: $resultString", e)
            Result.failure(Exception("Error processing JS response: ${e.message}. Raw response: $resultString"))
        }
    }

    // Convenience methods that use the generic implementation
    fun sendPaymentTokenToWebView(
        webView: WebView,
        payload: PaymentTokenPayload,
        callback: ((Result<PaymentJsResponse>) -> Unit)? = null
    ) {
        Log.d(TAG, "Sending payment token: $payload")
        sendDataToWebView(webView, payload, PAYMENT_REQUEST_TYPE.PAYMENT_TOKEN, callback)
    }

    fun sendSubscriptionEventToWebView(
        webView: WebView,
        payload: Subscription,
        callback: ((Result<PaymentJsResponse>) -> Unit)? = null
    ) {
        sendDataToWebView(webView, payload, PAYMENT_REQUEST_TYPE.SUBSCRIPTION, callback)
    }

    fun getPlansFromWebView(
        webView: WebView,
        callback: ((Result<PaymentJsResponse>) -> Unit)? = null
    ) {
        // For getPlans, payload is null
        sendDataToWebView(webView, null, PAYMENT_REQUEST_TYPE.GET_PLANS, callback)
    }

    fun getSubscriptionsFromWebView(
        webView: WebView,
        callback: ((Result<PaymentJsResponse>) -> Unit)? = null
    ) {
        // For getSubscriptions, payload is null
        sendDataToWebView(webView, null, PAYMENT_REQUEST_TYPE.GET_SUBSCRIPTIONS) { result ->
            // Log the raw response for debugging
            result.onSuccess { response ->
                if (response.data != null) {
                    Log.d("Subscriptions", "Raw subscription response: ${response.data}")
                }
            }
            // Call the original callback
            callback?.invoke(result)
        }
    }
}
