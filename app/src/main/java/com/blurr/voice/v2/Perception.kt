package com.blurr.voice.v2

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Perception layer: safely extract a ScreenAnalysis from AccessibilityNodeInfo tree.
 *
 * Eyes is an internal helper to traverse the node tree without blocking the service.
 *
 * All accessibility parsing is wrapped in withTimeoutOrNull(2500L) and all exceptions are caught.
 */

data class ScreenAnalysis(
    val title: String = "",
    val textSummary: String = "",
    val nodeCount: Int = 0
)

object Eyes {
    private val TAG = "Eyes"

    /**
     * Extracts a pure-data ScreenAnalysis copy of the current UI within a bounded timeout.
     * Returns an empty ScreenAnalysis on failure, null-safe.
     */
    suspend fun scanScreen(service: AccessibilityService?): ScreenAnalysis {
        if (service == null) {
            return ScreenAnalysis()
        }
        return try {
            withContext(Dispatchers.IO) {
                // Ensure screen scanning can't block
                val result = withTimeoutOrNull(2500L) {
                    safeFetchRootAndAnalyze(service)
                }
                result ?: ScreenAnalysis()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "scanScreen error: ${t.localizedMessage}")
            ScreenAnalysis()
        }
    }

    private fun safeFetchRootAndAnalyze(service: AccessibilityService): ScreenAnalysis {
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow failed: ${t.localizedMessage}")
            null
        }

        root ?: return ScreenAnalysis()

        return try {
            val sb = StringBuilder()
            var count = 0
            traverse(root) { node ->
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                if (!text.isNullOrBlank()) {
                    sb.append(text).append(" ")
                } else if (!desc.isNullOrBlank()) {
                    sb.append(desc).append(" ")
                }
                count++
            }
            val title = root.contentDescription?.toString() ?: root.viewIdResourceName ?: ""
            ScreenAnalysis(title = title, textSummary = sb.toString().trim(), nodeCount = count)
        } catch (t: Throwable) {
            Log.w(TAG, "analyze failed: ${t.localizedMessage}")
            ScreenAnalysis()
        } finally {
            // Do not recycle root; accessibility framework manages it
        }
    }

    private fun traverse(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        try {
            action(node)
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val c = try { node.getChild(i) } catch (_: Throwable) { null }
                if (c != null) {
                    traverse(c, action)
                    try { c.recycle() } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "traverse child error: ${t.localizedMessage}")
        }
    }
}

suspend fun perceive(service: AccessibilityService?): ScreenAnalysis {
    return Eyes.scanScreen(service)
}
