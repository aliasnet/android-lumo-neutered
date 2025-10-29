package me.proton.android.lumo.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

private const val SCREENSHOT_DIR = "screenshots"

fun SemanticsNodeInteraction.saveScreenshot(fileName: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val directory = File(context.filesDir, SCREENSHOT_DIR)
    if (!directory.exists() && !directory.mkdirs()) {
        throw IllegalStateException("Unable to create screenshot directory at ${directory.absolutePath}")
    }

    val targetFile = File(directory, fileName)
    val bitmap = captureToImage().asAndroidBitmap()
    FileOutputStream(targetFile).use { outputStream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
            throw IllegalStateException("Failed to save screenshot to ${targetFile.absolutePath}")
        }
    }
}
