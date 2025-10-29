package me.proton.android.lumo.billing

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.withDecorView
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.R
import me.proton.android.lumo.billing.gateway.BillingGateway
import me.proton.android.lumo.domain.WebEvent
import me.proton.android.lumo.testing.waitForNodeWithTextDisplayed
import me.proton.android.lumo.testing.waitForToastText
import me.proton.android.lumo.testing.saveScreenshot
import me.proton.android.lumo.ui.components.PaymentDialog
import me.proton.android.lumo.ui.components.PaymentProcessingState
import me.proton.android.lumo.ui.theme.LumoTheme
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class BillingUnavailablePaymentDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun paymentDialogDisplaysGenericBillingUnavailableCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedMessage = context.getString(R.string.billing_unavailable_generic)
        val gateway = FakeBillingGateway(available = false)

        composeRule.setContent {
            LumoTheme {
                PaymentDialog(
                    visible = true,
                    billingGateway = gateway,
                    billingAvailable = false,
                    onDismiss = {}
                )
            }
        }

        composeRule.waitForNodeWithTextDisplayed(expectedMessage)
        composeRule.onNodeWithText(expectedMessage)
            .assertExists()
            .assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
@LargeTest
class BillingUnavailableMainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun showPaymentRequestedSurfacesToastAndDialogCopy() {
        val expectedMessage = composeRule.activity.getString(R.string.billing_unavailable_generic)

        composeRule.waitForToastText(expectedMessage) {
            composeRule.runOnUiThread {
                composeRule.activity.viewModel.setBillingAvailability(false)
                composeRule.activity.viewModel.onWebEvent(WebEvent.ShowPaymentRequested)
            }
        }

        composeRule.waitForNodeWithTextDisplayed(expectedMessage)
        onView(withText(expectedMessage))
            .inRoot(withDecorView(not(`is`(composeRule.activity.window.decorView))))
            .check(matches(isDisplayed()))

        composeRule.onNodeWithText(expectedMessage)
            .assertExists()
            .assertIsDisplayed()
    }
}

@RunWith(AndroidJUnit4::class)
class BillingUnavailableLocalizationScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun capturesSpanishBillingUnavailableScreenshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseContext = instrumentation.targetContext
        val locale = Locale("es", "ES")
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.setLocales(LocaleList(locale))
        val localizedContext = baseContext.createConfigurationContext(configuration)
        val expectedMessage = localizedContext.getString(R.string.billing_unavailable_generic)

        val gateway = FakeBillingGateway(available = false)

        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext) {
                LumoTheme {
                    PaymentDialog(
                        visible = true,
                        billingGateway = gateway,
                        billingAvailable = false,
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.waitForNodeWithTextDisplayed(expectedMessage)
        val messageNode = composeRule.onNodeWithText(expectedMessage)
        messageNode
            .assertExists()
            .assertIsDisplayed()
            .saveScreenshot("billing-unavailable-es.png")
    }
}

private class FakeBillingGateway(
    override val available: Boolean,
) : BillingGateway {

    private val productDetails = MutableStateFlow<List<ProductDetails>>(emptyList())
    private val refreshing = MutableStateFlow(false)
    private val paymentState = MutableStateFlow<PaymentProcessingState?>(null)

    override val productDetailsList: StateFlow<List<ProductDetails>> = productDetails
    override val isRefreshingPurchases: StateFlow<Boolean> = refreshing
    override val paymentProcessingState: StateFlow<PaymentProcessingState?> = paymentState

    override fun getSubscriptionStatus(): Triple<Boolean, Boolean, Long> = Triple(false, false, 0L)
    override fun refreshPurchaseStatus(forceRefresh: Boolean) {}
    override fun invalidateCache() {}
    override fun openSubscriptionManagementScreen(): Boolean = false
    override fun launchBillingFlowForProduct(productId: String, offerToken: String?, customerId: String?) {}
    override fun retryPaymentVerification() {}
    override fun resetPaymentState() {}
    override fun triggerSubscriptionRecovery() {}
}
