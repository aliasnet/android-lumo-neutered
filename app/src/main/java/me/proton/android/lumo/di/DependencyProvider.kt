package me.proton.android.lumo.di

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.billing.gateway.BillingGateway
import me.proton.android.lumo.data.repository.SubscriptionRepository
import me.proton.android.lumo.data.repository.SubscriptionRepositoryImpl
import me.proton.android.lumo.managers.BillingManagerWrapper
import java.lang.ref.WeakReference

/**
 * Simple dependency provider that replaces the factory pattern
 * This provides a clean way to manage dependencies without the complexity of full DI frameworks
 */
object DependencyProvider {

    private var billingManagerWrapper: BillingManagerWrapper? = null
    private var billingActivityRef: WeakReference<MainActivity>? = null
    private var billingInitialized = false
    private var subscriptionRepository: SubscriptionRepository? = null

    /**
     * Get or create the BillingManagerWrapper instance
     */
    fun getBillingManagerWrapper(activity: MainActivity): BillingManagerWrapper {
        val existingActivity = billingActivityRef?.get()
        val wrapper = if (billingManagerWrapper != null && existingActivity === activity) {
            billingManagerWrapper!!
        } else {
            BillingManagerWrapper(activity).also {
                billingManagerWrapper = it
                billingActivityRef = WeakReference(activity)
                billingInitialized = false
            }
        }

        if (!billingInitialized) {
            wrapper.initializeBilling()
            billingInitialized = true
        }

        return wrapper
    }

    fun getBillingGatewayFlow(activity: MainActivity): StateFlow<BillingGateway> =
        getBillingManagerWrapper(activity).billingGatewayFlow

    /**
     * Get or create the SubscriptionRepository instance
     */
    fun getSubscriptionRepository(
        mainActivity: MainActivity,
        billingGatewayFlow: StateFlow<BillingGateway>
    ): SubscriptionRepository {
        return subscriptionRepository ?: SubscriptionRepositoryImpl(
            context = mainActivity.applicationContext,
            mainActivity = mainActivity,
            billingGatewayFlow = billingGatewayFlow
        ).also {
            subscriptionRepository = it
        }
    }

}
