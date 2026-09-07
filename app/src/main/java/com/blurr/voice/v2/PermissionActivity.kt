package com.blurr.voice.v2

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Blank, zero-crash launcher activity used as a safe baseline.
 * Displays a single TextView and performs no heavy work.
 */
class PermissionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal UI: just a TextView to confirm the app launched.
        val tv = TextView(this).apply {
            text = "Sarkar AI Running"
            textSize = 18f
            setPadding(40, 40, 40, 40)
        }
        setContentView(tv)
    }
}
