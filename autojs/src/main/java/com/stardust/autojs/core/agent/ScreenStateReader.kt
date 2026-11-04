package com.stardust.autojs.core.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.capture.ScreenCaptureManager
import com.stardust.view.accessibility.AccessibilityNodeInfoHelper
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID

class ScreenStateReader {

    data class TargetDescription(val bounds: Bounds, val text: String?, val imageTemplatePath: String?)

    private val screenCaptureManager = ScreenCaptureManager()
    private var templateDir: File? = null

    fun describeClickTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (label != null) {
            return TargetDescription(bounds, label, null)
        }
        return TargetDescription(bounds, null, captureTemplate(service, bounds))
    }

    fun describeEditableTarget(service: AccessibilityService, node: AccessibilityNodeInfo): TargetDescription {
        val bounds = Bounds.fromRect(AccessibilityNodeInfoHelper.getBoundsInScreen(node))
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }
        } else null
        val label = hint ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        return TargetDescription(bounds, label, null)
    }

    private fun captureTemplate(service: AccessibilityService, bounds: Bounds): String? {
        return try {
            runBlocking {
                if (screenCaptureManager.screenCapture?.available != true) {
                    screenCaptureManager.requestScreenCapture(
                        service.applicationContext,
                        service.resources.configuration.orientation
                    )
                }
                val screen = screenCaptureManager.screenCapture?.captureImageWrapper() ?: return@runBlocking null
                val cropped = cropToBounds(screen, bounds) ?: return@runBlocking null
                val file = File(templateDirFor(service), "${UUID.randomUUID()}.png")
                ImageWrapper.ofBitmap(cropped).saveTo(file.absolutePath)
                file.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cropToBounds(image: ImageWrapper, bounds: Bounds): Bitmap? {
        val bitmap = image.bitmap
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val width = (bounds.right - bounds.left).coerceIn(1, bitmap.width - left)
        val height = (bounds.bottom - bounds.top).coerceIn(1, bitmap.height - top)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun templateDirFor(service: AccessibilityService): File {
        return templateDir ?: File(service.applicationContext.filesDir, "agent_templates").also {
            it.mkdirs()
            templateDir = it
        }
    }
}
