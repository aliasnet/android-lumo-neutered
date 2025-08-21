package me.proton.android.lumo.ui.components

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.R
import me.proton.android.lumo.billing.BillingManager
import me.proton.android.lumo.billing.PreviewBillingManager
import me.proton.android.lumo.ui.theme.Purple
import me.proton.android.lumo.ui.theme.DarkText
import me.proton.android.lumo.ui.theme.GrayText
import me.proton.android.lumo.viewmodels.SubscriptionViewModel
import me.proton.android.lumo.viewmodels.ViewModelFactory

private const val TAG = "PaymentDialog"

@Preview(showBackground = true)
@Composable
fun PaymentDialogPreview() {
    val showDialog = remember { mutableStateOf(true) }
    val previewBillingManager = remember { PreviewBillingManager.createForPreview() }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Purple,
            surface = Color.White,
            onSurface = DarkText,
            onSurfaceVariant = GrayText
        )
    ) {
        PaymentDialog(showDialog, previewBillingManager)
    }
}

@SuppressLint("DefaultLocale")
@Composable
fun PaymentDialog(
    showDialog: MutableState<Boolean>,
    billingManager: BillingManager
) {
    val context = LocalContext.current
    val mainActivity = context as? MainActivity
    
    // Create ViewModel using the modern factory approach
    val subscriptionViewModel: SubscriptionViewModel = viewModel(
        factory = ViewModelFactory(mainActivity ?: return)
    )
    
    // Collect state from ViewModel
    val isLoadingSubscriptions by subscriptionViewModel.isLoadingSubscriptions.collectAsStateWithLifecycle()
    val isLoadingPlans by subscriptionViewModel.isLoadingPlans.collectAsStateWithLifecycle()
    val subscriptions by subscriptionViewModel.subscriptions.collectAsStateWithLifecycle()
    val hasValidSubscription by subscriptionViewModel.hasValidSubscription.collectAsStateWithLifecycle()
    val planOptions by subscriptionViewModel.planOptions.collectAsStateWithLifecycle()
    val selectedPlan by subscriptionViewModel.selectedPlan.collectAsStateWithLifecycle()
    val planFeatures by subscriptionViewModel.planFeatures.collectAsStateWithLifecycle()
    val errorMessage by subscriptionViewModel.errorMessage.collectAsStateWithLifecycle()
    
    // Get payment processing state from BillingManager
    val paymentProcessingState by billingManager.paymentProcessingState.collectAsStateWithLifecycle()
    val isRefreshingPurchases by billingManager.isRefreshingPurchases.collectAsStateWithLifecycle()

    // Load subscriptions whenever dialog opens
    LaunchedEffect(showDialog.value) {
        if (showDialog.value) {
            // Refresh status from Google Play and load subscriptions
            subscriptionViewModel.refreshSubscriptionStatus()
        }
    }
    
    // Check for subscription sync mismatch after BOTH loading operations are complete
    LaunchedEffect(isLoadingSubscriptions, isRefreshingPurchases, hasValidSubscription) {
        if (!isLoadingSubscriptions && !isRefreshingPurchases && !hasValidSubscription) {
            Log.d(TAG, "Both loading operations complete, checking for subscription sync mismatch...")
            // Check if there's a mismatch that needs recovery
            if (subscriptionViewModel.checkSubscriptionSyncMismatch()) {
                // Trigger the recovery flow
                subscriptionViewModel.triggerSubscriptionRecovery()
            }
        }
    }

    if (showDialog.value) {
        // If payment is being processed, show that screen instead
        if (paymentProcessingState != null) {
            Dialog(
                onDismissRequest = {
                    // Don't allow dismissing during loading or verification
                    if (paymentProcessingState !is PaymentProcessingState.Loading &&
                        paymentProcessingState !is PaymentProcessingState.Verifying
                    ) {
                        showDialog.value = false
                        billingManager.resetPaymentState()
                    }
                },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(16.dp)),
                    color = Color.White
                ) {
                    PaymentProcessingScreen(
                        state = paymentProcessingState!!,
                        onRetry = { billingManager.retryPaymentVerification() },
                        onClose = {
                            showDialog.value = false
                            billingManager.resetPaymentState()
                        }
                    )
                }
            }

            return
        }

        // Check if user already has a valid subscription
        if (hasValidSubscription) {
            Dialog(
                onDismissRequest = { showDialog.value = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(16.dp)),
                    color = Color.White
                ) {
                    SubscriptionOverviewSection(
                        billingManager = billingManager,
                        subscriptions = subscriptions,
                        onClose = { showDialog.value = false }
                    )
                }
            }

            return
        }

        // --- Dialog UI for plan selection --- 
        Dialog(
            onDismissRequest = { showDialog.value = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .clip(RoundedCornerShape(16.dp)),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Close Button
                    IconButton(
                        onClick = { showDialog.value = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = GrayText
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Image(
                        painter = painterResource(id = R.drawable.lumo_cat_on_laptop),
                        contentDescription = "Lumo Plus",
                        modifier = Modifier.height(80.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = stringResource(id = R.string.payment_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = DarkText,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = stringResource(id = R.string.payment_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = GrayText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )

                    // --- Dynamic Content based on loading/error/success ---
                    when {
                        isLoadingSubscriptions -> {
                            // Show loading UI while checking subscriptions
                            CircularProgressIndicator(color = Purple)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(id = R.string.payment_checking),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GrayText
                            )
                        }

                        isLoadingPlans -> {
                            // Show loading UI
                            CircularProgressIndicator(color = Purple)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(id = R.string.payment_loading_plans),
                                style = MaterialTheme.typography.bodyMedium,
                                color = GrayText
                            )
                        }

                        errorMessage != null -> {
                            // Show error state
                            Text(
                                text= errorMessage ?: stringResource(id = R.string.error_generic),
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }

                        planOptions.isNotEmpty() -> {
                            // Features comparison table
                            if (planFeatures.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Empty space for feature name
                                    Spacer(modifier = Modifier.weight(1f))

                                    // Free column header
                                    Text(
                                        "Free",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = DarkText,
                                        modifier = Modifier.weight(0.8f),
                                        textAlign = TextAlign.Center
                                    )

                                    // Plus column header
                                    Text(
                                        "Plus",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Purple,
                                        modifier = Modifier.weight(0.8f),
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Display features
                                planFeatures.take(5).forEach { feature ->
                                    FeatureComparisonItem(feature)
                                }

                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            // Plan Selection Section
                            planOptions.forEach { plan ->
                                // Skip plans with no pricing info
                                if (plan.totalPrice.isNotEmpty()) {
                                    PlanSelectItem(
                                        plan = plan,
                                        isSelected = selectedPlan?.id == plan.id,
                                        onSelected = { subscriptionViewModel.selectPlan(plan) }
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                }
                            }

                            // Display error message if any
                            errorMessage?.let { errorMsg ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    errorMsg,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            Text(
                                stringResource(id = R.string.subscription_renewal),
                                fontSize = 12.sp,
                                color = Color.DarkGray,
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            // Continue Button (Purchase)
                            Button(
                                onClick = {
                                    selectedPlan?.let { planToPurchase ->
                                        if (planToPurchase.offerToken == null && planToPurchase.totalPrice.isEmpty()) {
                                            subscriptionViewModel.clearError()
                                            return@Button
                                        }
                                        Log.d(
                                            TAG,
                                            "Purchase button clicked for plan: ${planToPurchase.id}, ProductID: ${planToPurchase.productId}, OfferToken: ${planToPurchase.offerToken}"
                                        )
                                        billingManager.launchBillingFlowForProduct(
                                            planToPurchase.productId,
                                            planToPurchase.offerToken,
                                            planToPurchase.customerId
                                        )
                                    } ?: run {
                                        Log.w(TAG, "Purchase clicked but no plan selected.")
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Purple,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(24.dp),
                                enabled = selectedPlan != null && selectedPlan?.totalPrice?.isNotEmpty() == true
                            ) {
                                Text(stringResource(id = R.string.subscription_buy_lumo), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(
                                onClick = { showDialog.value = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = GrayText
                                )
                            ) {
                                Text(
                                    "Not now",
                                    fontSize = 14.sp
                                )
                            }
                        }

                        else -> {
                            // No plans available
                            Text(
                                text = stringResource(id = R.string.payment_no_plans_available),
                                color = GrayText,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
} 