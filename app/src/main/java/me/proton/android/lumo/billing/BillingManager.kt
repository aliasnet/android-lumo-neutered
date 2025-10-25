package me.proton.android.lumo.billing

import android.content.Intent
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.proton.android.lumo.MainActivity
import me.proton.android.lumo.R
import me.proton.android.lumo.models.InAppGooglePayload
import me.proton.android.lumo.models.Payment
import me.proton.android.lumo.models.PaymentJsResponse
import me.proton.android.lumo.models.PaymentTokenPayload
import me.proton.android.lumo.models.Subscription
import me.proton.android.lumo.models.SubscriptionPlan
import java.util.*
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.flow.asStateFlow
import me.proton.android.lumo.ui.components.PaymentProcessingState
import me.proton.android.lumo.utils.ErrorClassifier
import androidx.core.net.toUri
import me.proton.android.lumo.managers.BillingManagerWrapper

class BillingManager(
    private val activity: MainActivity?,
    private val billingCallbacks: BillingManagerWrapper.BillingCallbacks,
    ) {
    val isTestMode: Boolean = false

    private val TAG = "BillingManager"

    companion object {
        // Test product IDs from Google Play Billing documentation
        // Static test SKUs from: https://developer.android.com/google/play/billing/test
        private const val TEST_PRODUCT_ID =
            "android.test.purchased" // One-time purchase that succeeds

        // Production subscription product IDs (must match Google Play Console exactly)
        internal val SUBSCRIPTION_PLANS = listOf(
            SubscriptionPlan(
                productId = "giaplumo_lumo2025_1_renewing",
                planName = "1 Month",
                durationMonths = 1,
                description = "Monthly subscription" // Note: This is a constant, localized descriptions are handled in UI
            ),
            SubscriptionPlan(
                productId = "giaplumo_lumo2025_12_renewing",
                planName = "12 Months",
                durationMonths = 12,
                description = "Annual subscription (save 20%)" // Note: This is a constant, localized descriptions are handled in UI
            )
        )

        // Cache invalidation constants
        private const val CACHE_INVALIDATION_INTERVAL_MS = 60_000L // 1 minute
        private const val PURCHASE_REFRESH_INTERVAL_MS =
            30_000L // 30 seconds for active subscriptions
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.NotPurchased)
    private val _productDetailsList = MutableStateFlow<List<ProductDetails>>(emptyList())
    private val _isConnected = MutableStateFlow(false)
    private val _isSubscriptionRenewing = MutableStateFlow(false)
    protected var subscriptionExpiryTimeMillis: Long = 0L

    // Customer ID from selected plan
    protected var currentCustomerID: String? = null

    // New state for payment processing
    private val _paymentProcessingState = MutableStateFlow<PaymentProcessingState?>(null)
    val paymentProcessingState = _paymentProcessingState.asStateFlow()

    // State to track if purchase refresh is in progress
    private val _isRefreshingPurchases = MutableStateFlow(false)
    val isRefreshingPurchases = _isRefreshingPurchases.asStateFlow()

    // Cache management
    private var lastPurchaseQueryTime = 0L
    private var lastProductDetailsQueryTime = 0L
    private var periodicRefreshJob: kotlinx.coroutines.Job? = null
    private var cachedPurchases: List<Purchase> = emptyList()

    val purchaseState = _purchaseState.asStateFlow()
    val productDetailsList = _productDetailsList.asStateFlow()

    private val _selectedPlanIndex = MutableStateFlow(0)

    private fun markBillingUnavailable(reason: String, throwable: Throwable? = null) {
        val message = activity?.getString(R.string.billing_unavailable_generic)
            ?: "Billing unavailable"
        val logDetails = buildString {
            append("Billing interaction failed during $reason; continuing without billing support")
            throwable?.let { error ->
                error::class.simpleName?.let { append(" ($it)") }
            }
        }
        if (throwable != null) {
            Log.w(TAG, logDetails, throwable)
        } else {
            Log.i(TAG, logDetails)
        }
        _isConnected.value = false
        _purchaseState.value = PurchaseState.Error(message)
        val currentProcessingState = _paymentProcessingState.value
        if (currentProcessingState != null && currentProcessingState !is PaymentProcessingState.Success) {
            _paymentProcessingState.value = PaymentProcessingState.Error(message)
        }
    }

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(
            TAG,
            "onPurchasesUpdated: ${billingResult.responseCode}, ${billingResult.debugMessage}"
        )
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            _purchaseState.value = PurchaseState.Cancelled
        } else {
            markBillingUnavailable(
                "onPurchasesUpdated: response ${billingResult.responseCode}"
            )
        }
    }

    private val billingClient by lazy {
        activity?.let {
            try {
                BillingClient.newBuilder(it)
                    .setListener(purchasesUpdatedListener)
                    .enablePendingPurchases()
                    .build()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to create BillingClient - likely due to old Google Play Billing API",
                    e
                )
                // Return null so the app can continue without billing
                null
            }
        }
    }

    private var isConnected = false

    /**
     * Check if billing is available (i.e., BillingClient was created successfully)
     */
    fun isBillingAvailable(): Boolean {
        return billingClient != null
    }

    fun isConnected(): Boolean = _isConnected.value

    init {
        Log.d(
            TAG,
            "Initializing BillingManager in ${if (isTestMode) "TEST" else "PRODUCTION"} mode"
        )

        // Defensive check: ensure activity is not null
        when {
            activity == null -> {
                Log.e(TAG, "Activity is null - cannot initialize billing")
                _purchaseState.value = PurchaseState.Error(
                    activity?.getString(R.string.billing_application_context_unavailable)
                        ?: "Application context unavailable"
                )
            }

            else -> {
                runCatching {
                    initializeBilling()
                }.onFailure { error ->
                    Log.w(
                        TAG,
                        "Billing initialization failed; falling back to no-op implementation",
                        error
                    )
                    markBillingUnavailable("initialization", error)
                }
            }
        }
    }

    private fun initializeBilling() {
        Log.d(TAG, "Initializing billing client")

        try {
            when {
                billingClient == null -> {
                    Log.i(
                        TAG,
                        "Billing client is null - billing unavailable (likely old Google Play API)"
                    )
                    markBillingUnavailable("billing client creation")
                    return
                }

                else -> {
                    establishConnection()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during billing client initialization; continuing without billing", e)
            markBillingUnavailable("initializeBilling", e)
        }
    }

    protected fun establishConnection() {
        Log.d(TAG, "Starting billing client connection (Test mode: $isTestMode)")
        if (billingClient == null) {
            Log.e(TAG, "Billing client is null")
            return
        }

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client connected successfully")
                    _isConnected.value = true

                    // Check billing features support
                    val subscriptionsSupported =
                        billingClient?.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
                    Log.d(
                        TAG,
                        "Subscriptions supported: ${subscriptionsSupported?.responseCode == BillingClient.BillingResponseCode.OK}"
                    )

                    val productDetailsSupported =
                        billingClient?.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
                    Log.d(
                        TAG,
                        "Product details supported: ${productDetailsSupported?.responseCode == BillingClient.BillingResponseCode.OK}"
                    )

                    // Query both product details and existing purchases
                    querySubscriptions()
                    queryExistingPurchases()

                    // Start periodic refresh for subscription monitoring
                    startPeriodicRefresh()
                } else if (billingResult.responseCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
                    val debugMessage = billingResult.debugMessage ?: "Unknown billing error"
                    Log.i(TAG, "Billing unavailable: $debugMessage")
                    markBillingUnavailable("billing setup ($debugMessage)")
                } else {
                    val debugMessage = billingResult.debugMessage ?: "Unknown billing error"
                    Log.i(
                        TAG,
                        "Billing client connection failed: ${billingResult.responseCode} $debugMessage"
                    )
                    markBillingUnavailable("billing setup (${billingResult.responseCode})")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
                _isConnected.value = false
                // Retry connection after a delay
                coroutineScope.launch {
                    delay(1000)
                    establishConnection()
                }
            }
        })
    }

    private fun queryExistingPurchases(
        onComplete: ((List<Purchase>) -> Unit)? = null,
        forceRefresh: Boolean = false
    ) {
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot query purchases - billing client not connected")
            // If we're in a payment processing state, show error
            if (_paymentProcessingState.value != null) {
                _paymentProcessingState.value = PaymentProcessingState.Error(
                    activity?.getString(R.string.billing_service_not_connected)
                        ?: "Billing service not connected. Please try again."
                )
            }
            onComplete?.invoke(emptyList())
            return
        }

        val currentTime = System.currentTimeMillis()
        val cacheAge = currentTime - lastPurchaseQueryTime

        // Check if we should skip query due to recent cache
        if (!forceRefresh && cacheAge < CACHE_INVALIDATION_INTERVAL_MS && lastPurchaseQueryTime > 0) {
            Log.d(
                TAG,
                "Using cached purchase data (age: ${cacheAge}ms, ${cachedPurchases.size} purchases)"
            )
            onComplete?.invoke(cachedPurchases) // Return cached purchases
            return
        }

        Log.d(TAG, "Querying existing purchases (force: $forceRefresh, cache age: ${cacheAge}ms)")
        coroutineScope.launch {
            try {
                // For test mode, we use INAPP for standard test products
                val params = QueryPurchasesParams.newBuilder()
                    .setProductType(if (isTestMode) BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS)
                    .build()

                billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
                    Log.d(
                        TAG,
                        "Existing purchases query result: ${billingResult.responseCode}, purchases size: ${purchases.size}"
                    )

                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        // Update cache timestamp and store purchases on successful query
                        lastPurchaseQueryTime = currentTime
                        cachedPurchases = purchases

                        if (purchases.isNotEmpty()) {
                            // Reset subscription status before processing new data
                            _isSubscriptionRenewing.value = false
                            subscriptionExpiryTimeMillis = 0

                            for (purchase in purchases) {
                                Log.d(TAG, "Found purchase: ${purchase.products}")

                                // Check if the subscription is set to renew
                                val isAutoRenewing = purchase.isAutoRenewing
                                _isSubscriptionRenewing.value = isAutoRenewing
                                Log.d(TAG, "Subscription auto-renewing: $isAutoRenewing")

                                // Extract expiry time from subscription
                                try {
                                    // Get expiry date from purchase token (if available)
                                    val subscriptionInfo = Gson().fromJson(
                                        purchase.originalJson,
                                        JsonObject::class.java
                                    )
                                    Log.d(TAG, "Subscription info: $subscriptionInfo")
                                    val expiryTimeMillis =
                                        subscriptionInfo.get("expiryTimeMillis")?.asLong ?: 0

                                    if (expiryTimeMillis > 0) {
                                        subscriptionExpiryTimeMillis = expiryTimeMillis
                                        Log.d(
                                            TAG,
                                            "Subscription expires at: ${Date(expiryTimeMillis)}"
                                        )

                                        // Check if subscription is actually expired
                                        val currentTimeMillis = System.currentTimeMillis()
                                        if (expiryTimeMillis <= currentTimeMillis) {
                                            Log.w(
                                                TAG,
                                                "Subscription appears to be EXPIRED (expiry: ${
                                                    Date(expiryTimeMillis)
                                                }, current: ${Date(currentTimeMillis)})"
                                            )
                                            // Consider this as no active subscription
                                            _purchaseState.value = PurchaseState.NotPurchased
                                            _isSubscriptionRenewing.value = false
                                            continue
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing subscription expiry time", e)
                                }
                            }

                            // Only set to Purchased if we have valid, non-expired purchases
                            if (_purchaseState.value != PurchaseState.NotPurchased) {
                                _purchaseState.value = PurchaseState.Purchased
                                Log.d(
                                    TAG,
                                    "Purchase state set to Purchased - found ${purchases.size} active subscription(s)"
                                )
                            }
                        } else {
                            Log.d(TAG, "No existing purchases found")
                            // Reset subscription status
                            _isSubscriptionRenewing.value = false
                            subscriptionExpiryTimeMillis = 0
                            _purchaseState.value = PurchaseState.NotPurchased
                        }

                        // Call completion callback with successful result
                        onComplete?.invoke(purchases)
                    } else {
                        Log.e(
                            TAG,
                            "Failed to query purchases: ${billingResult.responseCode} ${billingResult.debugMessage}"
                        )

                        // If we're in a payment processing state during retry, show error
                        if (_paymentProcessingState.value is PaymentProcessingState.Loading) {
                            _paymentProcessingState.value = PaymentProcessingState.Error(
                                "Failed to query purchases: ${billingResult.debugMessage ?: "Unknown error"}"
                            )
                        }

                        // Call completion callback with empty list to indicate failure
                        onComplete?.invoke(emptyList())
                    }

                    // Mark refresh as complete
                    _isRefreshingPurchases.value = false
                    Log.d(TAG, "Purchase refresh completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during purchase query", e)
                // If we're in a payment processing state during retry, show error
                if (_paymentProcessingState.value is PaymentProcessingState.Loading) {
                    _paymentProcessingState.value = PaymentProcessingState.Error(
                        "Error querying purchases: ${e.message}"
                    )
                }
                onComplete?.invoke(emptyList())
                _isRefreshingPurchases.value = false
            }
        }
    }

    private fun querySubscriptions() {
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot query subscriptions - billing client not connected")
            establishConnection()
            return
        }

        Log.d(TAG, "Querying products (Test mode: $isTestMode)")

        if (isTestMode) {
            // For test mode, use the standard test product ID with INAPP type
            // These are special product IDs recognized by Google Play for testing
            Log.d(TAG, "Using standard Google test product: $TEST_PRODUCT_ID")
            val productList = listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(TEST_PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP) // Test products are INAPP, not SUBS
                    .build()
            )

            queryProductDetails(productList)
        } else {
            // For production, query all subscription plans
            val productList = SUBSCRIPTION_PLANS.map { plan ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(plan.productId)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            }

            queryProductDetails(productList)
        }
    }

    private fun queryProductDetails(productList: List<QueryProductDetailsParams.Product>) {
        if (productList.isEmpty()) {
            Log.e(TAG, "Cannot query products - empty product list")
            return
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Log.d(
            TAG,
            "Querying ${productList.size} products in ${if (isTestMode) "TEST" else "PRODUCTION"} mode"
        )


        val client = billingClient ?: run {
            markBillingUnavailable("queryProductDetails: billing client unavailable")
            return
        }

        runCatching {
            client.queryProductDetailsAsync(params) { billingResult, retrievedProductDetails ->
                val responseCodeName = when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> "OK (0)"
                    BillingClient.BillingResponseCode.ERROR -> "ERROR (1)"
                    BillingClient.BillingResponseCode.USER_CANCELED -> "USER_CANCELED (1)"
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE (2)"
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE (3)"
                    BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "ITEM_UNAVAILABLE (4)"
                    BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR (5)"
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "ITEM_ALREADY_OWNED (7)"
                    BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> "ITEM_NOT_OWNED (8)"
                    else -> "UNKNOWN (${billingResult.responseCode})"
                }

                Log.d(
                    TAG,
                    "Product details query result: $responseCodeName, debug: ${billingResult.debugMessage}"
                )
                Log.d(TAG, "Retrieved ${retrievedProductDetails.size} products")

                when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        Log.d(TAG, retrievedProductDetails.toString())
                        if (retrievedProductDetails.isNotEmpty()) {
                            // Store all retrieved product details
                            _productDetailsList.value = retrievedProductDetails

                            // Also select the first product as default
                            val firstProduct = retrievedProductDetails[0]
                            this.productDetails = firstProduct

                            // Log info about all retrieved products
                            retrievedProductDetails.forEach { product ->
                                Log.d(TAG, "Product details retrieved:")
                                Log.d(TAG, "- Product ID: ${product.productId}")
                                Log.d(TAG, "- Name: ${product.name}")
                                Log.d(TAG, "- Description: ${product.description}")

                                if (!isTestMode && product.subscriptionOfferDetails != null) {
                                    product.subscriptionOfferDetails?.forEach { offer ->
                                        Log.d(
                                            TAG,
                                            "Offer: ${offer.basePlanId}, token: ${offer.offerToken}"
                                        )
                                    }
                                }
                            }

                            // Update subscription plans with pricing information
                            updateSubscriptionPlansWithPricing(retrievedProductDetails)

                            // Select the default plan (first one)
                            selectPlan(0)
                        } else {
                            val productIds = productList.joinToString(", ") {
                                try {
                                    it.javaClass.getDeclaredMethod("zza").invoke(it) as? String
                                        ?: "unknown"
                                } catch (e: Exception) {
                                    "unknown"
                                }
                            }
                            Log.e(TAG, "No products found for IDs: $productIds")

                            if (isTestMode) {
                                // This is a common issue with test products, so don't show error to user in test mode
                                Log.i(
                                    TAG,
                                    "This is expected in the emulator or devices without proper Play Store setup"
                                )

                                // Instead of showing an error, we'll mark a special state for UI testing
                                // This allows the UI to continue showing the payment dialog with dummy data
                                _purchaseState.value =
                                    PurchaseState.NoProductsAvailable("Test mode active, showing UI demo")
                                return@queryProductDetailsAsync
                            } else {
                                Log.e(TAG, "Make sure the products are set up in Google Play Console")
                            }
                            _purchaseState.value = PurchaseState.Error("No products found")
                        }
                    }

                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                        Log.e(TAG, "Service disconnected, reconnecting...")
                        isConnected = false
                        establishConnection()
                    }

                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
                        Log.d(TAG, "Billing unavailable while querying products (SERVICE_UNAVAILABLE)")
                        markBillingUnavailable("queryProductDetails: SERVICE_UNAVAILABLE")
                    }

                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                        Log.d(TAG, "Billing unavailable while querying products (BILLING_UNAVAILABLE)")
                        markBillingUnavailable("queryProductDetails: BILLING_UNAVAILABLE")
                    }

                    else -> {
                        Log.e(
                            TAG,
                            "Failed to retrieve product details: ${billingResult.responseCode} ${billingResult.debugMessage}"
                        )
                        markBillingUnavailable(
                            "queryProductDetails: response ${billingResult.responseCode}"
                        )
                    }
                }
            }
        }.onFailure { error ->
            markBillingUnavailable("queryProductDetails", error)
        }
    }

    private var productDetails: ProductDetails? = null

    /**
     * Select a subscription plan by index
     * @param index Index of the plan in the SUBSCRIPTION_PLANS list
     * @return true if selection was successful, false otherwise
     */
    fun selectPlan(index: Int): Boolean {
        val productDetailsList = _productDetailsList.value

        // For test mode, there's only one plan
        if (isTestMode) {
            if (productDetailsList.isNotEmpty()) {
                this.productDetails = productDetailsList[0]
                _selectedPlanIndex.value = 0
                Log.d(TAG, "Selected test product: ${productDetailsList[0].productId}")
                return true
            }

            // Even in test mode with no products, allow plan selection for UI testing
            Log.i(TAG, "No test product details available, but allowing selection for UI testing")
            _selectedPlanIndex.value = 0
            return true
        }

        // For production mode, check if index is valid
        if (index < 0 || index >= SUBSCRIPTION_PLANS.size) {
            Log.e(
                TAG,
                "Invalid plan index: $index, must be between 0 and ${SUBSCRIPTION_PLANS.size - 1}"
            )
            return false
        }

        // Find the product details for the selected plan
        val planProductId = SUBSCRIPTION_PLANS[index].productId
        Log.d(TAG, "Selecting plan with product ID: $planProductId")

        val selectedProduct = productDetailsList.find { it.productId == planProductId }

        if (selectedProduct != null) {
            this.productDetails = selectedProduct
            _selectedPlanIndex.value = index
            Log.d(
                TAG,
                "Selected plan ${SUBSCRIPTION_PLANS[index].planName} with product ID $planProductId"
            )
            return true
        } else {
            Log.e(
                TAG,
                "No product details found for plan index $index with product ID $planProductId"
            )
            Log.d(TAG, "Available products: ${productDetailsList.map { it.productId }}")
            return false
        }
    }


    private fun handlePurchase(purchase: Purchase) {
        Log.d(TAG, "Handling purchase: ${purchase.purchaseState}")
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            _purchaseState.value = PurchaseState.Purchased
            // Set initial payment processing state
            _paymentProcessingState.value = PaymentProcessingState.Loading

            try {
                val activity = activity
                val productDetails = this.productDetails
                if (activity != null && productDetails != null) {
                    // Extract amount and currency from ProductDetails
                    val priceAmountMicros =
                        productDetails.oneTimePurchaseOfferDetails?.priceAmountMicros
                            ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.priceAmountMicros
                            ?: 0L
                    val amountInCents = (priceAmountMicros / 10_000).toInt() // $9.99 -> 999
                    val currencyCode = productDetails.oneTimePurchaseOfferDetails?.priceCurrencyCode
                        ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.priceCurrencyCode
                        ?: "USD"

                    Log.d(
                        TAG,
                        "Using Google Play pricing for payment: ${amountInCents / 100.0} $currencyCode (from priceAmountMicros: $priceAmountMicros)"
                    )

                    val inAppGooglePayload = InAppGooglePayload(
                        purchaseToken = purchase.purchaseToken,
                        customerID = currentCustomerID ?: "",
                        packageName = purchase.packageName,
                        productID = purchase.products.firstOrNull() ?: "",
                        orderID = purchase.orderId.toString()
                    )
                    Log.d(
                        TAG,
                        "Created InAppGooglePayload with customerID: ${currentCustomerID ?: "not set"}"
                    )
                    val payment = Payment(Type = "google", Details = inAppGooglePayload)
                    val paymentTokenPayload = PaymentTokenPayload(
                        Amount = amountInCents,
                        Currency = currencyCode,
                        Payment = payment
                    )
                    Log.d(TAG, "Sending to createPaymentToken: ${paymentTokenPayload ?: "not set"}")

                    activity.webView?.let { webView ->
                        // Update state to show we're processing with server
                        _paymentProcessingState.value = PaymentProcessingState.Verifying

                        billingCallbacks.sendPaymentTokenToWebView(
                            paymentTokenPayload
                        ) { result: Result<PaymentJsResponse> ->
                            // Now within the call back we can do whatever else we need to do...
                            result.onSuccess { paymentJsResponse ->
                                // Now we can access the data from the successful PaymentJsResponse
                                Log.d(TAG, "JavaScript result success: ${paymentJsResponse}")

                                // Check for error status in response
                                if (paymentJsResponse.status == "error") {
                                    Log.e(
                                        TAG,
                                        "Error in payment token response: ${paymentJsResponse.message}"
                                    )
                                    _paymentProcessingState.value = PaymentProcessingState.Error(
                                        paymentJsResponse.message
                                            ?: "Unknown error creating payment token"
                                    )
                                    return@sendPaymentTokenToWebView
                                }

                                val token = try {
                                    // If data is a JsonObject
                                    if (paymentJsResponse.data?.isJsonObject == true) {
                                        paymentJsResponse.data.asJsonObject?.get("Token")?.asString
                                    }
                                    // If data is directly a primitive (like a string)
                                    else if (paymentJsResponse.data?.isJsonPrimitive == true) {
                                        paymentJsResponse.data.asString
                                    } else {
                                        null
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing token from response", e)
                                    _paymentProcessingState.value = PaymentProcessingState.Error(
                                        "Error processing payment response: ${e.message}"
                                    )
                                    return@sendPaymentTokenToWebView
                                }

                                if (token != null) {
                                    val subscriptionPayload = Subscription(
                                        PaymentToken = token,
                                        Currency = currencyCode,
                                        Cycle = 1,
                                        Plans = mapOf("lumo2024" to 1),
                                        CouponCode = null,
                                        BillingAddress = null
                                    )

                                    // Final step - send subscription to activate
                                    billingCallbacks.sendSubscriptionEventToWebView(
                                        subscriptionPayload
                                    ) { subscriptionResult ->
                                        subscriptionResult.onSuccess { response ->
                                            // Success! Payment is fully processed
                                            Log.d(
                                                TAG,
                                                "Subscription activated successfully: ${response}"
                                            )
                                            _paymentProcessingState.value =
                                                PaymentProcessingState.Success
                                        }.onFailure { error ->
                                            Log.e(TAG, "Subscription request failed", error)
                                            _paymentProcessingState.value =
                                                PaymentProcessingState.Error(
                                                    "Could not activate subscription: ${error.message}"
                                                )
                                        }
                                    }
                                } else {
                                    Log.e(TAG, "Token was null in payment response")
                                    _paymentProcessingState.value = PaymentProcessingState.Error(
                                        "Payment token was not found in the server response"
                                    )
                                }
                            }.onFailure { error ->
                                Log.e(TAG, "Payment token request failed", error)
                                // Use professional error classification
                                val errorInfo = ErrorClassifier.classify(error)
                                val context = activity ?: return@onFailure
                                _paymentProcessingState.value = when (errorInfo.type) {
                                    ErrorClassifier.ErrorType.Network,
                                    ErrorClassifier.ErrorType.Timeout,
                                    ErrorClassifier.ErrorType.SSL -> PaymentProcessingState.NetworkError(
                                        errorInfo.getUserMessage(context)
                                    )

                                    else -> PaymentProcessingState.Error(
                                        errorInfo.getUserMessage(context)
                                    )
                                }
                            }
                        }
                    } ?: run {
                        Log.e(TAG, "WebView not available for payment processing")
                        _paymentProcessingState.value = PaymentProcessingState.Error(
                            "Could not communicate with subscription servers"
                        )
                    }
                } else {
                    Log.e(TAG, "Activity or product details not available")
                    _paymentProcessingState.value = PaymentProcessingState.Error(
                        "Application error: could not process payment"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending payment token to WebView", e)
                _paymentProcessingState.value = PaymentProcessingState.Error(
                    "Error processing payment: ${e.message}"
                )
            }
            // --- End send payment token ---

            // Acknowledge the purchase if it hasn't been acknowledged yet
            if (!purchase.isAcknowledged) {
                Log.d(TAG, "Acknowledging purchase")
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                coroutineScope.launch {
                    val client = billingClient ?: run {
                        markBillingUnavailable("acknowledgePurchase: billing client unavailable")
                        return@launch
                    }

                    runCatching {
                        client.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                            Log.d(
                                TAG,
                                "Acknowledge result: ${billingResult.responseCode} ${billingResult.debugMessage}"
                            )
                            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                                Log.d(TAG, "Purchase acknowledged")
                            } else {
                                Log.e(
                                    TAG,
                                    "Failed to acknowledge purchase: ${billingResult.debugMessage}"
                                )
                                markBillingUnavailable(
                                    "acknowledgePurchase: response ${billingResult.responseCode}"
                                )
                            }
                        }
                    }.onFailure { error ->
                        Log.d(TAG, "Acknowledge purchase failed: ${error.message}")
                        markBillingUnavailable("acknowledgePurchase", error)
                    }
                }
            }
        }
    }

    // Add this new method to update subscription plans with pricing
    private fun updateSubscriptionPlansWithPricing(productDetailsList: List<ProductDetails>) {
        if (isTestMode) {
            // In test mode, set some default prices
            SUBSCRIPTION_PLANS.forEach { plan ->
                plan.formattedPrice = when (plan.durationMonths) {
                    1 -> "$9.99"
                    12 -> "$99.99"
                    else -> "$9.99"
                }
                plan.periodText = when (plan.durationMonths) {
                    1 -> "month"
                    12 -> "year"
                    else -> "month"
                }
            }
            return
        }

        // For each product details, find matching subscription plan and update price
        productDetailsList.forEach { productDetails ->
            val matchingPlan = SUBSCRIPTION_PLANS.find { it.productId == productDetails.productId }

            if (matchingPlan != null) {
                // Find the right subscription offer for this plan
                val expectedPeriod = when (matchingPlan.durationMonths) {
                    1 -> "P1M" // Period of 1 Month
                    12 -> "P1Y" // Period of 1 Year
                    else -> null
                }

                // Get default offer or matching offer by period
                val offer = if (expectedPeriod != null) {
                    productDetails.subscriptionOfferDetails?.find { offer ->
                        offer.pricingPhases.pricingPhaseList.any { phase ->
                            phase.billingPeriod == expectedPeriod
                        }
                    }
                } else null

                // Fallback to first offer if no match
                val selectedOffer = offer ?: productDetails.subscriptionOfferDetails?.firstOrNull()

                // Get pricing from the first pricing phase (usually the recurring price)
                val pricingPhase = selectedOffer?.pricingPhases?.pricingPhaseList?.firstOrNull()

                if (pricingPhase != null) {
                    matchingPlan.formattedPrice = pricingPhase.formattedPrice
                    matchingPlan.price = pricingPhase.formattedPrice
                    matchingPlan.priceAmountMicros = pricingPhase.priceAmountMicros

                    // Set readable period text
                    matchingPlan.periodText = when (pricingPhase.billingPeriod) {
                        "P1D" -> activity?.getString(R.string.period_day) ?: "day"
                        "P1W" -> activity?.getString(R.string.period_week) ?: "week"
                        "P1M" -> activity?.getString(R.string.period_month) ?: "month"
                        "P3M" -> activity?.getString(R.string.period_quarter) ?: "quarter"
                        "P6M" -> activity?.getString(R.string.period_6_months) ?: "6 months"
                        "P1Y" -> activity?.getString(R.string.period_year) ?: "year"
                        else -> pricingPhase.billingPeriod
                    }

                    Log.d(
                        TAG,
                        "Updated plan ${matchingPlan.planName} with price: ${matchingPlan.formattedPrice} per ${matchingPlan.periodText}"
                    )
                }
            }
        }
    }

    /**
     * Opens the Google Play subscription management screen
     * Call this when the user already has an active subscription
     * @return true if the screen was launched successfully
     */
    fun openSubscriptionManagementScreen(): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = "https://play.google.com/store/account/subscriptions".toUri()

            activity?.let {
                if (intent.resolveActivity(it.packageManager) != null) {
                    it.startActivity(intent)
                    Log.d(TAG, "Opened Google Play subscription management screen")
                    true
                } else {
                    // Fallback if the URI method doesn't work
                    val playStoreIntent = Intent(Intent.ACTION_VIEW)
                    playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    playStoreIntent.data =
                        "https://play.google.com/store/apps/details?id=com.android.vending".toUri()

                    if (playStoreIntent.resolveActivity(it.packageManager) != null) {
                        it.startActivity(playStoreIntent)
                        Log.d(TAG, "Opened Google Play Store")
                        true
                    } else {
                        Log.e(TAG, "Could not open Google Play Store")
                        false
                    }
                }
            } ?: run {
                Log.e(TAG, "Activity not available")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening subscription management", e)
            false
        }
    }

    /**
     * Check if the user has an active subscription
     * This method will return true even for cancelled subscriptions that are still within their valid period
     */
    fun hasActiveSubscription(): Boolean {
        return purchaseState.value is PurchaseState.Purchased
    }

    /**
     * Check if the user has an active and renewing subscription
     * This only returns true for subscriptions that are set to automatically renew
     */
    fun hasRenewingSubscription(): Boolean {
        return hasActiveSubscription() && _isSubscriptionRenewing.value
    }

    /**
     * Get information about the subscription status
     * @return A tuple containing (isActive, isRenewing, expiryTimeMillis)
     */
    fun getSubscriptionStatus(): Triple<Boolean, Boolean, Long> {
        val isActive = hasActiveSubscription()
        val isRenewing = _isSubscriptionRenewing.value
        return Triple(isActive, isRenewing, subscriptionExpiryTimeMillis)
    }

    /**
     * Refresh the purchase status to get the latest subscription information
     * Call this when displaying subscription information to ensure it's up-to-date
     */
    fun refreshPurchaseStatus(forceRefresh: Boolean = false) {
        if (!_isConnected.value) {
            Log.d(TAG, "Cannot refresh purchases - billing client not connected")
            establishConnection()
            return
        }

        Log.d(TAG, "Refreshing purchase status (force: $forceRefresh)...")
        _isRefreshingPurchases.value = true
        queryExistingPurchases(forceRefresh = forceRefresh)
    }

    /**
     * Start periodic refresh for active subscriptions to catch expiration/cancellation faster
     */
    private fun startPeriodicRefresh() {
        // Cancel existing job
        periodicRefreshJob?.cancel()

        periodicRefreshJob = coroutineScope.launch {
            while (true) {
                delay(PURCHASE_REFRESH_INTERVAL_MS)

                // Only refresh if we have an active subscription
                if (hasActiveSubscription()) {
                    Log.d(TAG, "Periodic refresh - checking subscription status")
                    queryExistingPurchases(forceRefresh = true)
                } else {
                    Log.d(TAG, "No active subscription - skipping periodic refresh")
                }
            }
        }
    }

    /**
     * Stop periodic refresh
     */
    private fun stopPeriodicRefresh() {
        periodicRefreshJob?.cancel()
        periodicRefreshJob = null
    }

    /**
     * Invalidate all cached data and force a fresh query
     */
    fun invalidateCache() {
        Log.d(TAG, "Invalidating subscription cache")
        lastPurchaseQueryTime = 0L
        lastProductDetailsQueryTime = 0L
        cachedPurchases = emptyList()
        refreshPurchaseStatus(forceRefresh = true)
    }

    /**
     * Initiates the billing flow for a specific product ID and offer token.
     *
     * @param productId The Google Play product ID to purchase.
     * @param offerToken The specific offer token for the subscription plan (can be null for base plans).
     * @param customerID The customer ID to associate with the purchase (can be null if not applicable).
     */
    fun launchBillingFlowForProduct(
        productId: String,
        offerToken: String?,
        customerID: String? = null
    ) {
        runCatching {
            if (!_isConnected.value) {
                Log.e(TAG, "Cannot launch billing flow - billing client not connected.")
                _purchaseState.value = PurchaseState.Error("Billing service not connected.")
                return
            }
            val currentActivity = activity
            if (currentActivity == null) {
                Log.e(TAG, "Cannot launch billing flow - activity is null.")
                _purchaseState.value = PurchaseState.Error("Application context error.")
                return
            }

            // Store customerID for use during purchase processing
            this.currentCustomerID = customerID

            Log.d(
                TAG,
                "Attempting to launch purchase flow for Product ID: $productId, Offer Token: $offerToken, Customer ID: $customerID"
            )

            val productDetails = _productDetailsList.value.find { it.productId == productId }

            if (productDetails == null) {
                Log.e(TAG, "Product details not found for $productId. Cannot launch flow.")
                _purchaseState.value = PurchaseState.Error("Selected plan details not found.")
                return
            }

            // Construct the ProductDetailsParams builder
            val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)

            // Find and set the offer token if provided and valid
            if (offerToken != null && productDetails.subscriptionOfferDetails != null) {
                val selectedOffer =
                    productDetails.subscriptionOfferDetails?.find { it.offerToken == offerToken }
                if (selectedOffer != null) {
                    Log.d(TAG, "Found matching offer token: $offerToken")
                    productDetailsParamsBuilder.setOfferToken(selectedOffer.offerToken)
                } else {
                    Log.w(
                        TAG,
                        "Provided offer token '$offerToken' not found for product $productId. Check plan configuration or user eligibility."
                    )
                    _purchaseState.value =
                        PurchaseState.Error("Selected plan offer is not available. Please try again or select a different plan.")
                }
            } else if (offerToken != null && productDetails.subscriptionOfferDetails == null) {
                Log.w(
                    TAG,
                    "Offer token '$offerToken' provided, but product $productId has no subscription offer details."
                )
                _purchaseState.value = PurchaseState.Error("Plan configuration error.")
                return
            } else {
                Log.d(TAG, "No specific offer token provided or needed for product $productId.")
            }

            val productDetailsParamsList = listOf(productDetailsParamsBuilder.build())

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .setObfuscatedAccountId(customerID!!)
                .build()

            val client = billingClient ?: run {
                markBillingUnavailable("launchBillingFlowForProduct: billing client unavailable")
                return
            }

            val billingResult = client.launchBillingFlow(currentActivity, billingFlowParams)

            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(
                    TAG,
                    "Failed to launch billing flow: ${billingResult.responseCode} ${billingResult.debugMessage}"
                )
                markBillingUnavailable(
                    "launchBillingFlowForProduct: response ${billingResult.responseCode}"
                )
            } else {
                Log.d(TAG, "Billing flow launched successfully.")
            }
        }.onFailure { error ->
            Log.d(TAG, "Launch billing flow failed: ${error.message}")
            markBillingUnavailable("launchBillingFlowForProduct", error)
        }
    }

    /**
     * Retry the payment process for a purchase that had an error
     */
    fun retryPaymentVerification() {
        try {
            val currentState = _paymentProcessingState.value

            if (currentState is PaymentProcessingState.SubscriptionRecovery) {
                Log.d(
                    TAG,
                    "Retrying subscription recovery - processing existing Google Play purchase"
                )
                triggerSubscriptionRecovery()
            } else {
                Log.d(TAG, "Retrying payment verification")
                // Set state back to loading
                _paymentProcessingState.value = PaymentProcessingState.Loading

                // Query existing purchases to get the purchase token again - force refresh
                queryExistingPurchases(
                    onComplete = { purchases ->
                        if (purchases.isNotEmpty()) {
                            // Found purchase - check if it's already acknowledged and processed
                            val purchase = purchases.first()
                            Log.d(TAG, "Found purchase to retry: ${purchase.products}")

                            // If we already have a purchased state, the purchase is valid
                            if (_purchaseState.value is PurchaseState.Purchased) {
                                Log.d(
                                    TAG,
                                    "Purchase already processed successfully, setting state to Success"
                                )
                                _paymentProcessingState.value = PaymentProcessingState.Success
                            } else {
                                // Process the purchase normally
                                handlePurchase(purchase)
                            }
                        } else {
                            Log.e(TAG, "No purchases found during retry")
                            _paymentProcessingState.value = PaymentProcessingState.Error(
                                "No active purchase found to retry. Please try purchasing again."
                            )
                        }
                    },
                    forceRefresh = true // Always get fresh data for retry
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrying payment verification", e)
            _paymentProcessingState.value = PaymentProcessingState.Error(
                "Error retrying payment: ${e.message}"
            )
        }
    }


    /**
     * Reset the payment processing state
     */
    fun resetPaymentState() {
        _paymentProcessingState.value = null
    }

    /**
     * Trigger subscription recovery for API/Google Play mismatch
     */
    fun triggerSubscriptionRecovery() {
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot process recovery - billing client not connected")
            _paymentProcessingState.value =
                PaymentProcessingState.Error("Billing service not connected")
            return
        }

        Log.d(TAG, "Triggering subscription recovery")
        _paymentProcessingState.value = PaymentProcessingState.SubscriptionRecovery(
            "We found your subscription in Google Play but it's not synced with our servers. Let's fix that!"
        )

        // Process existing purchase directly - force refresh to get latest data
        queryExistingPurchases(
            onComplete = { purchases ->
                if (purchases.isNotEmpty()) {
                    val purchase = purchases.first()
                    Log.d(TAG, "Processing recovery for purchase: ${purchase.products}")

                    // Extract and set customer ID from existing purchase
                    purchase.accountIdentifiers?.obfuscatedAccountId?.let { accountId ->
                        Log.d(TAG, "Using obfuscated account ID from purchase: $accountId")
                        currentCustomerID = accountId
                    } ?: Log.w(TAG, "No obfuscated account ID found in purchase")

                    // Set to loading and process through normal payment flow
                    _paymentProcessingState.value = PaymentProcessingState.Loading
                    handlePurchase(purchase)
                } else {
                    _paymentProcessingState.value = PaymentProcessingState.Error(
                        "No active Google Play subscription found to recover"
                    )
                }
            },
            forceRefresh = true // Always get fresh data for recovery
        )
    }

    /**
     * Helper function to identify network errors using professional error classification
     */
    private fun isNetworkError(throwable: Throwable): Boolean {
        return ErrorClassifier.isNetworkError(throwable)
    }

    sealed class PurchaseState {
        data object NotPurchased : PurchaseState()
        data object Purchased : PurchaseState()
        object Cancelled : PurchaseState()
        data class Error(val message: String) : PurchaseState()
        data class NoProductsAvailable(val message: String) : PurchaseState()
    }
}
