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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*

/**
 * AccessibilityService that boots the Agent loop and manages foreground boundaries, notifications,
 * permission prompts, and an on-device offline speech recognizer.
 *
 * Defensive: initializes TTS and SpeechRecognizer on the main thread, protects against
 * lifecycle races, and guards all external calls to avoid uncaught exceptions.
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

    // Text-to-Speech
    private var tts: TextToSpeech? = null
    @Volatile private var isTtsSpeaking: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "Service onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        startForegroundSafe()
        try {
            agent = Agent(this, scope)
            agent?.start()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start Agent: ${t.localizedMessage}")
        }
        initOverlay()
        initTts()
        initSpeechRecognizer()
        startListeningSafe()
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for now.
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service onDestroy")
        try {
            stopListeningSafe()
            // Ensure tts shutdown on main thread
            try {
                mainHandler.post {
                    try {
                        tts?.stop()
                        tts?.shutdown()
                    } catch (t: Throwable) {
                        Log.w(TAG, "TTS shutdown error on main thread: ${t.localizedMessage}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error posting TTS shutdown to main thread: ${t.localizedMessage}")
            }
            destroySpeechRecognizer()
            overlay?.hide()
            agent?.stop()
            scope.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping agent: ${t.localizedMessage}")
        }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                overlay?.show()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "initOverlay failed: ${t.localizedMessage}")
            overlay = null
        }
    }

    // Text-to-Speech
    private fun initTts() {
        try {
            // Ensure initialization happens on main thread
            mainHandler.post {
                try {
                    tts = TextToSpeech(applicationContext) { status ->
                        try {
                            if (status == TextToSpeech.SUCCESS) {
                                val res = tts?.setLanguage(Locale.getDefault())
                                Log.i(TAG, "TTS initialized, language set result: $res")
                                // Attach utterance listener to pause/resume recognizer
                                try {
                                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                                        override fun onStart(utteranceId: String?) {
                                            isTtsSpeaking = true
                                            Log.d(TAG, "TTS onStart: $utteranceId")
                                            // Stop recognizer while speaking
                                            stopListeningSafe()
                                        }

                                        override fun onDone(utteranceId: String?) {
                                            isTtsSpeaking = false
                                            Log.d(TAG, "TTS onDone: $utteranceId")
                                            // Resume listening only if agent isn't stopped
                                            scope.launch {
                                                delay(300)
                                                try {
                                                    if (agent?.state?.stopped != true) startListeningSafe()
                                                } catch (t: Throwable) {
                                                    Log.w(TAG, "resume listening after TTS failed: ${t.localizedMessage}")
                                                }
                                            }
                                        }

                                        override fun onError(utteranceId: String?) {
                                            isTtsSpeaking = false
                                            Log.w(TAG, "TTS onError: $utteranceId")
                                            scope.launch {
                                                delay(300)
                                                try {
                                                    if (agent?.state?.stopped != true) startListeningSafe()
                                                } catch (t: Throwable) {
                                                    Log.w(TAG, "resume listening after TTS error failed: ${t.localizedMessage}")
                                                }
                                            }
                                        }
                                    })
                                } catch (t: Throwable) {
                                    Log.w(TAG, "setOnUtteranceProgressListener failed: ${t.localizedMessage}")
                                }
                            } else {
                                Log.w(TAG, "TTS initialization failed: status=$status")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "TTS setup error: ${t.localizedMessage}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "init TTS creation failed on main thread: ${t.localizedMessage}")
                    tts = null
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "initTts failed: ${t.localizedMessage}")
            tts = null
        }
    }

    // Speak and show on overlay + notification
    fun speakAndShow(text: String) {
        try {
            val display = "Response: ${text.trim()}"
            // Update UI on main thread
            mainHandler.post {
                try {
                    overlay?.update(display)
                    updateNotification(display)
                } catch (t: Throwable) {
                    Log.w(TAG, "speakAndShow UI update failed: ${t.localizedMessage}")
                }
            }
            try {
                // Ensure recognizer is paused before speaking
                stopListeningSafe()
                val utteranceId = UUID.randomUUID().toString()
                // Speak on main thread
                mainHandler.post {
                    try {
                        tts?.let { t ->
                            try {
                                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                            } catch (t: Throwable) {
                                Log.w(TAG, "TTS speak call failed: ${t.localizedMessage}")
                            }
                        } ?: Log.w(TAG, "speakAndShow: TTS is null, cannot speak")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error during TTS speak post: ${t.localizedMessage}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TTS speak failed: ${t.localizedMessage}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "speakAndShow failed: ${t.localizedMessage}")
        }
    }

    // Speech recognizer integration
    private fun initSpeechRecognizer() {
        try {
            mainHandler.post {
                try {
                    if (SpeechRecognizer.isRecognitionAvailable(this)) {
                        try {
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
                                            // Only restart if not speaking via TTS
                                            try {
                                                if (!isTtsSpeaking && agent?.state?.stopped != true) startListeningSafe()
                                            } catch (t: Throwable) {
                                                Log.w(TAG, "restart listening after error failed: ${t.localizedMessage}")
                                            }
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
                                        // Restart listening if not speaking
                                        scope.launch {
                                            delay(300)
                                            try {
                                                if (!isTtsSpeaking && agent?.state?.stopped != true) startListeningSafe()
                                            } catch (t: Throwable) {
                                                Log.w(TAG, "restart listening after results failed: ${t.localizedMessage}")
                                            }
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
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to create SpeechRecognizer instance: ${t.localizedMessage}")
                            destroySpeechRecognizer()
                        }
                    } else {
                        Log.w(TAG, "Speech recognition not available on this device")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "initSpeechRecognizer inner failed: ${t.localizedMessage}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "initSpeechRecognizer failed: ${t.localizedMessage}")
            destroySpeechRecognizer()
        }
    }

    private fun startListeningSafe() {
        try {
            if (isTtsSpeaking) return
            val sr = speechRecognizer
            val intent = listeningIntent
            if (sr != null && intent != null) {
                mainHandler.post {
                    try {
                        sr.startListening(intent)
                    } catch (t: SecurityException) {
                        Log.w(TAG, "startListeningSafe SecurityException: ${t.localizedMessage}")
                    } catch (t: Throwable) {
                        Log.w(TAG, "startListeningSafe failed on main thread: ${t.localizedMessage}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "startListeningSafe failed: ${t.localizedMessage}")
        }
    }

    private fun stopListeningSafe() {
        try {
            speechRecognizer?.let { sr ->
                mainHandler.post {
                    try {
                        sr.cancel()
                    } catch (t: Throwable) {
                        Log.w(TAG, "stopListeningSafe cancel failed: ${t.localizedMessage}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "stopListeningSafe failed: ${t.localizedMessage}")
        }
    }

    private fun destroySpeechRecognizer() {
        try {
            speechRecognizer?.let { sr ->
                mainHandler.post {
                    try {
                        sr.cancel()
                    } catch (_: Throwable) {}
                    try {
                        sr.destroy()
                    } catch (_: Throwable) {}
                }
            }
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
