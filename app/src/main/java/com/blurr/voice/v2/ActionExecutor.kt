package com.blurr.voice.v2

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ACT layer: safe UI actions performed through AccessibilityNodeInfo.
 * - Protects against infinite loops with a maxFailures parameter.
 * - Catches and logs exceptions; returns boolean success/failure.
 */

sealed class ScreenAction {
    data class ClickByText(val text: String) : ScreenAction()
    data class TypeText(val viewId: String?, val text: String) : ScreenAction()
    data class ScrollByText(val text: String) : ScreenAction()
    object Done : ScreenAction()
}

class ActionExecutor(private val service: AccessibilityService?) {
    private val TAG = "ActionExecutor"
    private var consecutiveFailures = 0
    private val maxFailures = 5

    suspend fun execute(action: ScreenAction): Boolean {
        if (service == null) return false
        return withContext(Dispatchers.IO) {
            try {
                if (consecutiveFailures >= maxFailures) {
                    Log.w(TAG, "Max failures reached; refusing to continue executing actions")
                    return@withContext false
                }
                val success = when (action) {
                    is ScreenAction.ClickByText -> clickByText(action.text)
                    is ScreenAction.TypeText -> typeText(action.viewId, action.text)
                    is ScreenAction.ScrollByText -> scrollByText(action.text)
                    is ScreenAction.Done -> {
                        Log.i(TAG, "Action DONE requested")
                        true
                    }
                }
                if (!success) {
                    consecutiveFailures++
                } else {
                    consecutiveFailures = 0
                }
                success
            } catch (t: Throwable) {
                Log.w(TAG, "execute action exception: ${t.localizedMessage}")
                consecutiveFailures++
                false
            }
        }
    }

    private fun clickByText(text: String): Boolean {
        val root = try { service?.rootInActiveWindow } catch (_: Throwable) { null }
        root ?: return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node = nodes?.firstOrNull()
            val result = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            nodes?.forEach { try { it.recycle() } catch (_: Throwable) {} }
            node?.let { try { it.recycle() } catch (_: Throwable) {} }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "clickByText failed: ${t.localizedMessage}")
            false
        }
    }

    private fun typeText(viewId: String?, text: String): Boolean {
        val root = try { service?.rootInActiveWindow } catch (_: Throwable) { null }
        root ?: return false
        return try {
            val node = if (!viewId.isNullOrBlank()) {
                try { root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull() } catch (_: Throwable) { null }
            } else {
                // fallback: first focusable editable
                findFirstEditable(root)
            }
            val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
            val result = node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
            try { node?.recycle() } catch (_: Throwable) {}
            result
        } catch (t: Throwable) {
            Log.w(TAG, "typeText failed: ${t.localizedMessage}")
            false
        }
    }

    private fun scrollByText(text: String): Boolean {
        val root = try { service?.rootInActiveWindow } catch (_: Throwable) { null }
        root ?: return false
        return try {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val node = nodes?.firstOrNull()
            val result = node?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
            nodes?.forEach { try { it.recycle() } catch (_: Throwable) {} }
            node?.let { try { it.recycle() } catch (_: Throwable) {} }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "scrollByText failed: ${t.localizedMessage}")
            false
        }
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val isEditable = node.isEditable
                if (isEditable) return node
                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val c = try { node.getChild(i) } catch (_: Throwable) { null }
                    if (c != null) queue.add(c)
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "findFirstEditable error: ${t.localizedMessage}")
            null
        }
    }
}
