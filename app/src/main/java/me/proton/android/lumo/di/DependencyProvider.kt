package me.proton.android.lumo.di

import android.content.Context
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.data.repository.SubscriptionRepository
import me.proton.android.lumo.data.repository.SubscriptionRepositoryImpl
import me.proton.android.lumo.managers.BillingManagerWrapper

/**
 * Simple dependency provider that replaces the factory pattern
 * This provides a clean way to manage dependencies without the complexity of full DI frameworks
 */
object DependencyProvider {

    private var billingManagerWrapper: BillingManagerWrapper? = null
    private var subscriptionRepository: SubscriptionRepository? = null

    /**
     * Get or create the BillingManagerWrapper instance
     */
    fun getBillingManagerWrapper(activity: MainActivity): BillingManagerWrapper {
        return billingManagerWrapper ?: BillingManagerWrapper(activity).also {
            billingManagerWrapper = it
        }
    }

    /**
     * Get or create the SubscriptionRepository instance
     */
    fun getSubscriptionRepository(
        mainActivity: MainActivity,
        billingManagerWrapper: BillingManagerWrapper
    ): SubscriptionRepository {
        return subscriptionRepository ?: SubscriptionRepositoryImpl(
            context = mainActivity.applicationContext,
            mainActivity = mainActivity,
            billingManager = billingManagerWrapper.getBillingManager()
        ).also {
            subscriptionRepository = it
        }
    }

}
