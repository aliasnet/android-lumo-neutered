package me.proton.android.lumo.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.R
import me.proton.android.lumo.managers.BillingManagerWrapper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class BillingManagerTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `billing unavailable surfaces generic message`() {
        val activity = mockk<MainActivity>(relaxed = true) {
            every { getString(R.string.billing_unavailable_generic) } returns "Billing unavailable"
        }
        val callbacks = mockk<BillingManagerWrapper.BillingCallbacks>(relaxed = true)

        mockkStatic(BillingClient::class)

        val builder = mockk<BillingClient.Builder>(relaxed = true)
        val billingClient = mockk<BillingClient>(relaxed = true)

        every { BillingClient.newBuilder(activity) } returns builder
        every { builder.setListener(any()) } returns builder
        every { builder.enablePendingPurchases() } returns builder
        every { builder.build() } returns billingClient
        every { billingClient.isFeatureSupported(any()) } returns BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.OK)
            .setDebugMessage("")
            .build()
        every { billingClient.startConnection(any()) } answers {
            val listener = invocation.args[0] as BillingClientStateListener
            val result = BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
                .setDebugMessage("missing play services")
                .build()
            listener.onBillingSetupFinished(result)
        }

        val manager = BillingManager(activity, callbacks)

        val state = manager.purchaseState.value
        assertIs<BillingManager.PurchaseState.Error>(state)
        assertEquals("Billing is currently unavailable. Please try again later.", state.message)
    }
}
