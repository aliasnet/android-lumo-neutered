package me.proton.android.lumo.testing

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.waitUntil
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector

// Increased default timeout to stabilise CI emulator waits observed during Turn 21.
private const val DEFAULT_TIMEOUT_MS = 7_500L

fun AndroidComposeTestRule<*, *>.waitForToastText(
    text: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    trigger: (() -> Unit)? = null,
) {
    trigger?.invoke()
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    waitUntil(timeoutMillis) {
        device.findObject(UiSelector().textContains(text)).exists()
    }
}

fun AndroidComposeTestRule<*, *>.waitForNodeWithTextDisplayed(
    text: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    useUnmergedTree: Boolean = false,
) {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(
            text = text,
            substring = false,
            ignoreCase = false,
            useUnmergedTree = useUnmergedTree,
        ).fetchSemanticsNodes().isNotEmpty()
    }
}
