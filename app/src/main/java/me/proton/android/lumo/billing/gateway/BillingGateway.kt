package me.proton.android.lumo.billing.gateway

import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.flow.StateFlow
import me.proton.android.lumo.ui.components.PaymentProcessingState

/**
 * Lightweight facade over the billing implementation used by the UI and domain layers.
 */
interface BillingGateway {
    /** Whether Google Play Billing is ready for use. */
    val available: Boolean

    /** Flow of product details exposed by the billing implementation. */
    val productDetailsList: StateFlow<List<ProductDetails>>

    /** Flow representing purchase refresh progress. */
    val isRefreshingPurchases: StateFlow<Boolean>

    /** Flow of payment processing states (null when idle). */
    val paymentProcessingState: StateFlow<PaymentProcessingState?>

    /** Current subscription status tuple (isActive, isAutoRenewing, expiryTimeMillis). */
    fun getSubscriptionStatus(): Triple<Boolean, Boolean, Long>

    /** Requests a refresh of purchase state. */
    fun refreshPurchaseStatus(forceRefresh: Boolean = false)

    /** Invalidates the cached billing data. */
    fun invalidateCache()

    /** Opens the Google Play subscription management screen when available. */
    fun openSubscriptionManagementScreen(): Boolean

    /** Launches the purchase flow for the supplied product. */
    fun launchBillingFlowForProduct(productId: String, offerToken: String?, customerId: String?)

    /** Retries payment verification after a transient error. */
    fun retryPaymentVerification()

    /** Resets any in-progress payment state. */
    fun resetPaymentState()

    /** Triggers subscription recovery for mismatched account states. */
    fun triggerSubscriptionRecovery()
}
