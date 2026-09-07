package com.blurr.voice.v2

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * MainActivity: lightweight launcher activity that displays a simple status text.
 * Keeps startup zero-crash by performing no heavy initialization.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val tv = TextView(this).apply {
            text = "Sarkar AI Running"
            textSize = 18f
            setPadding(40, 40, 40, 40)
        }
        setContentView(tv)
    }
}
