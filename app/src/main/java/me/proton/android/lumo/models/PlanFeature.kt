package me.proton.android.lumo.models

/**
 * Represents a feature from the Entitlements array, with the Text split into parts
 */
data class PlanFeature(
    val name: String,              // Feature name (first part of Text)
    val freeText: String,          // Free tier description (second part of Text)
    val paidText: String,          // Paid tier description (third part of Text)
    val iconName: String           // From IconName field
) 