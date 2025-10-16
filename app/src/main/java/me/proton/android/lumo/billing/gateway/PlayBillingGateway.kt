package me.proton.android.lumo.billing.gateway

import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.flow.StateFlow
import me.proton.android.lumo.billing.BillingManager
import me.proton.android.lumo.ui.components.PaymentProcessingState

/**
 * Google Play-backed implementation of [BillingGateway].
 */
class PlayBillingGateway(
    private val billingManager: BillingManager
) : BillingGateway {

    override val available: Boolean
        get() = billingManager.isBillingAvailable() && billingManager.isConnected()

    override val productDetailsList: StateFlow<List<ProductDetails>>
        get() = billingManager.productDetailsList

    override val isRefreshingPurchases: StateFlow<Boolean>
        get() = billingManager.isRefreshingPurchases

    override val paymentProcessingState: StateFlow<PaymentProcessingState?>
        get() = billingManager.paymentProcessingState

    override fun getSubscriptionStatus(): Triple<Boolean, Boolean, Long> =
        billingManager.getSubscriptionStatus()

    override fun refreshPurchaseStatus(forceRefresh: Boolean) {
        billingManager.refreshPurchaseStatus(forceRefresh)
    }

    override fun invalidateCache() {
        billingManager.invalidateCache()
    }

    override fun openSubscriptionManagementScreen(): Boolean =
        billingManager.openSubscriptionManagementScreen()

    override fun launchBillingFlowForProduct(
        productId: String,
        offerToken: String?,
        customerId: String?
    ) {
        billingManager.launchBillingFlowForProduct(productId, offerToken, customerId)
    }

    override fun retryPaymentVerification() {
        billingManager.retryPaymentVerification()
    }

    override fun resetPaymentState() {
        billingManager.resetPaymentState()
    }

    override fun triggerSubscriptionRecovery() {
        billingManager.triggerSubscriptionRecovery()
    }
}
