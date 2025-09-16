package me.proton.android.lumo.interfaces

import android.webkit.WebView
import me.proton.android.lumo.models.PaymentJsResponse

/**
 * Interface for providing WebView operations to repositories
 */
interface WebViewProvider {
    /**
     * Get the current WebView instance
     */
    fun getCurrentWebView(): WebView?

    /**
     * Get plans from WebView
     */
    fun getPlansFromWebView(callback: (Result<PaymentJsResponse>) -> Unit)

    /**
     * Get subscriptions from WebView
     */
    fun getSubscriptionsFromWebView(callback: (Result<PaymentJsResponse>) -> Unit)
}
