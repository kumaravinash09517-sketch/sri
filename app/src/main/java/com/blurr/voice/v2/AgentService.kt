package com.blurr.voice.v2

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * AccessibilityService that boots the Agent loop and manages foreground boundaries, notifications,
 * and permission prompts without throwing exceptions.
 *
 * Keep this class minimal; move heavy logic to Agent, Perception, GeminiApi, and ActionExecutor.
 */
class AgentService : AccessibilityService() {
    private val TAG = "AgentService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agent: Agent? = null
    private val NOTIF_CHANNEL_ID = "sarkar_ai_receptionist_channel"
    private val NOTIF_ID = 1001

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        startForegroundSafe()
        agent = Agent(this, scope)
        agent?.start()
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        try {
            agent?.stop()
            scope.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping agent: ${t.localizedMessage}")
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        // AccessibilityService handles binding internally
        return super.onBind(intent)
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.let {
                    val channel = NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        "Sarkar AI Receptionist",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Foreground channel for Sarkar AI Receptionist service"
                    }
                    it.createNotificationChannel(channel)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "createNotificationChannel failed: ${t.localizedMessage}")
        }
    }

    private fun startForegroundSafe() {
        try {
            val notification = buildNotification()
            startForeground(NOTIF_ID, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to startForeground: ${t.localizedMessage}")
        }
    }

    private fun buildNotification(): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(Settings.ACTION_SETTINGS),
            pendingFlags
        )

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Sarkar AI Receptionist")
            .setContentText("Listening for commands")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Action to open overlay permission screen
        val overlayIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val overlayPending = PendingIntent.getActivity(this, 1, overlayIntent, pendingFlags)
        builder.addAction(NotificationCompat.Action.Builder(0, "Overlay", overlayPending).build())

        // Action to open app notification settings
        val notifIntent = Intent().apply {
            action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        val notifPending = PendingIntent.getActivity(this, 2, notifIntent, pendingFlags)
        builder.addAction(NotificationCompat.Action.Builder(0, "Notifications", notifPending).build())

        // Action to request ignore battery optimizations
        val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        val batteryPending = PendingIntent.getActivity(this, 3, batteryIntent, pendingFlags)
        builder.addAction(NotificationCompat.Action.Builder(0, "Battery", batteryPending).build())

        return builder.build()
    }

    // Expose the AccessibilityService context to the Agent and helpers
    fun getServiceInstance(): AccessibilityService? {
        return this
    }
}
