package com.blurr.voice.v2

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Lightweight overlay HUD shown via WindowManager (requires SYSTEM_ALERT_WINDOW permission).
 * If the permission isn't granted the caller should fall back to notification updates.
 */
class OverlayHUD(private val context: Context) {
    private var windowManager: WindowManager? = null
    private var view: View? = null

    fun show() {
        try {
            if (view != null) return
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val inflater = LayoutInflater.from(context)
            val inflated = inflater.inflate(android.R.layout.simple_list_item_1, null)
            val tv = inflated.findViewById<TextView>(android.R.id.text1)
            tv.text = "Listening..."
            tv.setBackgroundColor(0x88000000.toInt())
            tv.setTextColor(0xFFFFFFFF.toInt())
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 50
            }
            windowManager?.addView(inflated, params)
            view = inflated
        } catch (_: Throwable) {
            // ignore failures; caller will fallback
            view = null
        }
    }

    fun update(text: String) {
        try {
            val tv = view?.findViewById<TextView>(android.R.id.text1)
            tv?.text = text
        } catch (_: Throwable) {}
    }

    fun hide() {
        try {
            if (view != null) {
                windowManager?.removeView(view)
                view = null
            }
        } catch (_: Throwable) {
            view = null
        }
    }
}
