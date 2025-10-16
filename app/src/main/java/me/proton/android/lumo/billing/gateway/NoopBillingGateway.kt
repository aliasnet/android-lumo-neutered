package me.proton.android.lumo.billing.gateway

import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.proton.android.lumo.ui.components.PaymentProcessingState

/**
 * Safe fallback that disables billing features entirely.
 */
class NoopBillingGateway : BillingGateway {

    private val emptyProducts = MutableStateFlow(emptyList<ProductDetails>())
    private val refreshing = MutableStateFlow(false)
    private val paymentState = MutableStateFlow<PaymentProcessingState?>(null)

    override val available: Boolean = false

    override val productDetailsList: StateFlow<List<ProductDetails>> = emptyProducts

    override val isRefreshingPurchases: StateFlow<Boolean> = refreshing

    override val paymentProcessingState: StateFlow<PaymentProcessingState?> = paymentState

    override fun getSubscriptionStatus(): Triple<Boolean, Boolean, Long> =
        Triple(false, false, 0L)

    override fun refreshPurchaseStatus(forceRefresh: Boolean) = Unit

    override fun invalidateCache() = Unit

    override fun openSubscriptionManagementScreen(): Boolean = false

    override fun launchBillingFlowForProduct(
        productId: String,
        offerToken: String?,
        customerId: String?
    ) = Unit

    override fun retryPaymentVerification() = Unit

    override fun resetPaymentState() = Unit

    override fun triggerSubscriptionRecovery() = Unit
}
