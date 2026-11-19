package com.vetspantry.tabletbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Injects taps/swipes/text on behalf of the host computer. Requires the
 * accessibility service to be enabled by the user (Android provides no other
 * non-root way for an app to synthesize input).
 */
object InputInjector {

    @Volatile var service: AccessibilityService? = null

    fun available(): Boolean = service != null

    fun tap(xNorm: Double, yNorm: Double): Boolean {
        val s = service ?: return false
        val size = screenSize(s) ?: return false
        val path = Path().apply {
            moveTo((xNorm * size.first).toFloat(), (yNorm * size.second).toFloat())
        }
        return dispatchStroke(s, path, 60)
    }

    fun longPress(xNorm: Double, yNorm: Double): Boolean {
        val s = service ?: return false
        val size = screenSize(s) ?: return false
        val path = Path().apply {
            moveTo((xNorm * size.first).toFloat(), (yNorm * size.second).toFloat())
        }
        return dispatchStroke(s, path, 600)
    }

    fun swipe(x1: Double, y1: Double, x2: Double, y2: Double, ms: Long): Boolean {
        val s = service ?: return false
        val size = screenSize(s) ?: return false
        val path = Path().apply {
            moveTo((x1 * size.first).toFloat(), (y1 * size.second).toFloat())
            lineTo((x2 * size.first).toFloat(), (y2 * size.second).toFloat())
        }
        return dispatchStroke(s, path, ms.coerceIn(50, 10000))
    }

    fun globalAction(action: String): Boolean {
        val s = service ?: return false
        val id = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return try {
            s.performGlobalAction(id)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setText(value: String, append: Boolean): Boolean {
        val s = service ?: return false
        val node = focusedEditable(s) ?: return false
        val current = node.text?.toString() ?: ""
        val next = if (append) current + value else value
        return performSetText(node, next)
    }

    fun deleteChars(count: Int): Boolean {
        val s = service ?: return false
        val node = focusedEditable(s) ?: return false
        val current = node.text?.toString() ?: ""
        if (current.isEmpty()) return true
        val next = current.dropLast(count.coerceIn(1, current.length))
        return performSetText(node, next)
    }

    private fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    private fun focusedEditable(s: AccessibilityService): AccessibilityNodeInfo? {
        var node = try {
            s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (e: Exception) {
            null
        }
        if (node == null || !node.isEditable) {
            node = findEditable(try { s.rootInActiveWindow } catch (e: Exception) { null }, 0)
        }
        return node
    }

    private fun findEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            }
            findEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun screenSize(s: AccessibilityService): Pair<Int, Int>? {
        return try {
            val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION") val display = wm.defaultDisplay
            val p = Point()
            display.getRealSize(p)
            Pair(p.x, p.y)
        } catch (e: Exception) {
            null
        }
    }

    private fun dispatchStroke(s: AccessibilityService, path: Path, durationMs: Long): Boolean {
        return try {
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            s.dispatchGesture(gesture, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}

class InputAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        InputInjector.service = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        InputInjector.service = null
    }
}
