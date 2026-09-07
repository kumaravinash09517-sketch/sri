package com.blurr.voice.v2

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Lightweight PermissionActivity that guides the user through runtime permission granting
 * for RECORD_AUDIO, overlay, and accessibility enabling. It is intentionally minimal and
 * resilient to missing permissions.
 */
class PermissionActivity : AppCompatActivity() {
    private val TAG = "PermissionActivity"

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Minimal UI using buttons created programmatically to avoid layouts
        val btnAudio = Button(this).apply { text = "Grant Audio" }
        val btnOverlay = Button(this).apply { text = "Grant Overlay" }
        val btnAccessibility = Button(this).apply { text = "Open Accessibility Settings" }

        val layout = androidx.appcompat.widget.LinearLayoutCompat(this).apply {
            orientation = androidx.appcompat.widget.LinearLayoutCompat.VERTICAL
            addView(btnAudio)
            addView(btnOverlay)
            addView(btnAccessibility)
            setPadding(30, 60, 30, 60)
        }

        setContentView(layout)

        btnAudio.setOnClickListener {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        btnOverlay.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    } else {
                        Toast.makeText(this, "Overlay already granted", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Overlay permission not required on this OS", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "overlay click failed: ${t.localizedMessage}")
            }
        }

        btnAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to open accessibility settings: ${t.localizedMessage}")
            }
        }
    }
}
