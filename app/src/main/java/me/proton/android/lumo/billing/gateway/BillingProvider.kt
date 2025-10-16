package me.proton.android.lumo.billing.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.billing.BillingManager
import me.proton.android.lumo.managers.BillingManagerWrapper

private const val BILLING_CONNECT_TIMEOUT_MS = 2000L

/**
 * Creates [BillingGateway] instances, falling back to [NoopBillingGateway] on failure.
 */
object BillingProvider {

    suspend fun get(
        activity: MainActivity?,
        callbacks: BillingManagerWrapper.BillingCallbacks
    ): BillingGateway = withContext(Dispatchers.IO) {
        if (activity == null) {
            return@withContext NoopBillingGateway()
        }

        return@withContext runCatching {
            withTimeout(BILLING_CONNECT_TIMEOUT_MS) {
                val billingManager = withContext(Dispatchers.Main) {
                    BillingManager(activity = activity, billingCallbacks = callbacks)
                }

                val gateway = PlayBillingGateway(billingManager)
                while (!gateway.available) {
                    delay(50)
                }
                gateway
            }
        }.getOrElse { NoopBillingGateway() }
    }
}
