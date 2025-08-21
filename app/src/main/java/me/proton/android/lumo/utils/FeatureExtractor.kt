package me.proton.android.lumo.utils

import android.util.Log
import com.google.gson.JsonObject
import me.proton.android.lumo.models.PlanFeature

private const val TAG = "FeatureExtractor"

/**
 * Helper for extracting feature information from the API response
 */
object FeatureExtractor {
    
    /**
     * Extracts plan features from the API response
     *
     * @param planObject The JSON object containing the plan data
     * @return List of extracted PlanFeature objects
     */
    fun extractPlanFeatures(planObject: JsonObject): List<PlanFeature> {
        val features = mutableListOf<PlanFeature>()
        
        if (planObject.has("Entitlements") && planObject.get("Entitlements").isJsonArray) {
            val entitlementsArray = planObject.getAsJsonArray("Entitlements")
            
            for (i in 0 until entitlementsArray.size()) {
                try {
                    val entitlement = entitlementsArray[i].asJsonObject
                    if (entitlement.get("Type")?.asString == "description") {
                        val textParts = entitlement.get("Text")?.asString?.split("::")
                        if (textParts != null && textParts.size >= 3) {
                            val feature = PlanFeature(
                                name = textParts[0],
                                freeText = textParts[1],
                                paidText = textParts[2],
                                iconName = entitlement.get("IconName")?.asString ?: "checkmark"
                            )
                            features.add(feature)
                            Log.d(TAG, "Added feature: ${feature.name}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing entitlement", e)
                }
            }
        }
        
        return features
    }
} 