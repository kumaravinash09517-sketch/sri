package com.blurr.voice.v2

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Light-weight AccessibilityService stub used to avoid heavy initialization on startup.
 * All heavy components (TTS, SpeechRecognizer, Agent loop) are intentionally disabled
 * in this baseline to guarantee the APK launches cleanly on MIUI/HyperOS devices.
 */
class AgentService : AccessibilityService() {
    private val TAG = "AgentService"

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AgentService created - running in stub mode")
        // Intentionally do NOT initialize TTS, SpeechRecognizer, or start background Agent loop.
        // This ensures the service does not cause crashes on sensitive OEM ROMs.
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AgentService connected - stub mode")
        // No further work in baseline.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op in stub mode.
    }

    override fun onInterrupt() {
        // No-op in stub mode.
    }

    override fun onDestroy() {
        try {
            Log.i(TAG, "AgentService destroyed - stub mode")
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
