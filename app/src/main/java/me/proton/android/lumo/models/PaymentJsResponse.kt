package me.proton.android.lumo.models

import com.google.gson.JsonElement

/**
 * Represents the structured response expected from JavaScript payment/subscription calls.
 */
data class PaymentJsResponse(
    val status: String, // e.g., "success", "error"
    val data: JsonElement? = null, // The actual data on success (can be any JSON structure)
    val message: String? = null // Error message on failure
) 