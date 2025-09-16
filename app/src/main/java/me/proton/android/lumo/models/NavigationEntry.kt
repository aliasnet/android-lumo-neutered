package me.proton.android.lumo.models

// --- Navigation Tracking ---
data class NavigationEntry(
    val url: String,
    val timestamp: Long = System.currentTimeMillis(),
    val domain: String = try {
        java.net.URI(url).host ?: ""
    } catch (e: Exception) {
        ""
    }
) 