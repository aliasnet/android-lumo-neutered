package me.proton.android.lumo.billing

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.constructor.MockKConstructorScope.Companion.constructedWith
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.billing.gateway.BillingProvider
import me.proton.android.lumo.billing.gateway.NoopBillingGateway
import me.proton.android.lumo.managers.BillingManagerWrapper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class BillingProviderTest {

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
    fun `missing play services returns noop gateway`() = runTest {
        val activity = mockk<MainActivity>(relaxed = true)
        val callbacks = mockk<BillingManagerWrapper.BillingCallbacks>(relaxed = true)

        mockkConstructor(BillingManager::class)
        every { constructedWith<BillingManager>(any<MainActivity?>(), any()) } throws IllegalStateException("Play services unavailable")

        val gateway = BillingProvider.get(activity, callbacks)

        assertIs<NoopBillingGateway>(gateway)
        assertFalse(gateway.available)
    }
}
