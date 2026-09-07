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

/**
 * Lightweight PermissionActivity that guides the user through runtime permission granting
 * for RECORD_AUDIO, overlay, and accessibility enabling. This version is defensive and
 * guards against SecurityException / NPE when interacting with system Settings and services.
 */
class PermissionActivity : AppCompatActivity() {
    private val TAG = "PermissionActivity"

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        try {
            if (granted) {
                Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audio permission callback failed: ${t.localizedMessage}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal UI using buttons created programmatically to avoid layout inflation errors
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
            try {
                // Safe request; exact flow handled by the registered callback
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            } catch (t: SecurityException) {
                Log.w(TAG, "SecurityException requesting audio permission: ${t.localizedMessage}")
                Toast.makeText(this, "Unable to request audio permission", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "Unexpected error requesting audio permission: ${t.localizedMessage}")
            }
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
            } catch (t: SecurityException) {
                Log.w(TAG, "SecurityException opening overlay settings: ${t.localizedMessage}")
                Toast.makeText(this, "Unable to open overlay settings", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "overlay click failed: ${t.localizedMessage}")
            }
        }

        btnAccessibility.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (t: SecurityException) {
                Log.w(TAG, "SecurityException opening accessibility settings: ${t.localizedMessage}")
                Toast.makeText(this, "Unable to open accessibility settings", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "failed to open accessibility settings: ${t.localizedMessage}")
            }
        }
    }
}
