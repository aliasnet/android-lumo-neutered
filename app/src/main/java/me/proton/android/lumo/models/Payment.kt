package me.proton.android.lumo.models


data class InAppGooglePayload(
    val purchaseToken: String,
    val customerID: String,
    val packageName: String,
    val productID: String,
    val orderID: String
)

data class Payment(
    val Type: String,
    val Details: InAppGooglePayload? = null
)

data class PaymentTokenPayload(
    val Amount: Int,
    val Currency: String,
    val PaymentMethodID: String? = null,
    val Payment: Payment? = null
)

data class Subscription(
    val PaymentToken: String?,
    val Cycle: Int,
    val Currency: String,
    val Plans: Map<String, Int>,
    val CouponCode: String?,
    val BillingAddress: String?
)