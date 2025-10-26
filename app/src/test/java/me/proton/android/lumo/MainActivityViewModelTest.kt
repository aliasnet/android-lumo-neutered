package me.proton.android.lumo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.proton.android.lumo.domain.WebEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivityViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `billing unavailable web request surfaces generic message`() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainActivityViewModel(context)

        viewModel.setBillingAvailability(false)

        viewModel.events.test {
            viewModel.onWebEvent(WebEvent.ShowPaymentRequested)
            this@runTest.advanceUntilIdle()

            val toastEvent = awaitItem() as UiEvent.ShowToast
            assertEquals(context.getString(R.string.billing_unavailable_generic), toastEvent.message)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(viewModel.uiState.value.showPaymentDialog)
    }
}
