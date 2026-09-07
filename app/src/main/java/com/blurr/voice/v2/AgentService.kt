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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * AccessibilityService that boots the Agent loop and manages foreground boundaries, notifications,
 * permission prompts, and an on-device offline speech recognizer.
 *
 * The service posts a persistent notification and attempts to show a small overlay HUD (if overlay
 * permission is granted) to show live "Listening: ..." partial results. If overlay is not available
 * it falls back to updating the notification content text.
 */
class AgentService : AccessibilityService() {
    private val TAG = "AgentService"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var agent: Agent? = null
    private val NOTIF_CHANNEL_ID = "sarkar_ai_receptionist_channel"
    private val NOTIF_ID = 1001

    // Speech recognizer
    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningIntent: Intent? = null
    private var overlay: OverlayHUD? = null

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
        initOverlay()
        initSpeechRecognizer()
        startListeningSafe()
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        try {
            stopListeningSafe()
            destroySpeechRecognizer()
            overlay?.hide()
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
            val notification = buildNotification("Listening for commands")
            startForeground(NOTIF_ID, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to startForeground: ${t.localizedMessage}")
        }
    }

    private fun buildNotification(content: String): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(Settings.ACTION_SETTINGS),
            pendingFlags
        )

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("Sarkar AI Receptionist")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        // Add quick actions to open permissions/settings
        try {
            val overlayIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            val overlayPending = PendingIntent.getActivity(this, 1, overlayIntent, pendingFlags)
            builder.addAction(NotificationCompat.Action.Builder(0, "Overlay", overlayPending).build())
        } catch (_: Throwable) {}

        try {
            val notifIntent = Intent().apply {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            val notifPending = PendingIntent.getActivity(this, 2, notifIntent, pendingFlags)
            builder.addAction(NotificationCompat.Action.Builder(0, "Notifications", notifPending).build())
        } catch (_: Throwable) {}

        try {
            val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            val batteryPending = PendingIntent.getActivity(this, 3, batteryIntent, pendingFlags)
            builder.addAction(NotificationCompat.Action.Builder(0, "Battery", batteryPending).build())
        } catch (_: Throwable) {}

        return builder.build()
    }

    private fun updateNotification(content: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIF_ID, buildNotification(content))
        } catch (t: Throwable) {
            Log.w(TAG, "updateNotification failed: ${t.localizedMessage}")
        }
    }

    // Overlay HUD
    private fun initOverlay() {
        try {
            overlay = OverlayHUD(this.applicationContext)
            if (Settings.canDrawOverlays(this)) {
                overlay?.show()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "initOverlay failed: ${t.localizedMessage}")
            overlay = null
        }
    }

    // Speech recognizer integration
    private fun initSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "onReadyForSpeech")
                        }

                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "onBeginningOfSpeech")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            Log.d(TAG, "onEndOfSpeech")
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "SpeechRecognizer error: $error")
                            // restart listening after a small delay to be resilient
                            scope.launch {
                                delay(500)
                                startListeningSafe()
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            Log.i(TAG, "onResults: $text")
                            overlay?.update("Listening: $text")
                            updateNotification("Listening: $text")
                            // Forward final recognized speech to agent for THINK
                            try {
                                agent?.processUserSpeech(text)
                            } catch (t: Throwable) {
                                Log.w(TAG, "processUserSpeech failed: ${t.localizedMessage}")
                            }
                            // Restart listening
                            scope.launch {
                                delay(300)
                                startListeningSafe()
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = partial?.firstOrNull() ?: ""
                            Log.d(TAG, "onPartialResults: $text")
                            overlay?.update("Listening: $text")
                            updateNotification("Listening: $text")
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                listeningIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            } else {
                Log.w(TAG, "Speech recognition not available on this device")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "initSpeechRecognizer failed: ${t.localizedMessage}")
            destroySpeechRecognizer()
        }
    }

    private fun startListeningSafe() {
        try {
            val sr = speechRecognizer
            val intent = listeningIntent
            if (sr != null && intent != null) {
                sr.startListening(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startListeningSafe failed: ${t.localizedMessage}")
        }
    }

    private fun stopListeningSafe() {
        try {
            speechRecognizer?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "stopListeningSafe failed: ${t.localizedMessage}")
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
            listeningIntent = null
        } catch (t: Throwable) {
            Log.w(TAG, "destroySpeechRecognizer failed: ${t.localizedMessage}")
        }
    }

    // Expose the AccessibilityService context to the Agent and helpers
    fun getServiceInstance(): AccessibilityService? {
        return this
    }
}
