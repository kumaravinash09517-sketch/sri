package com.blurr.voice.v2

import android.accessibilityservice.AccessibilityService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Agent orchestrator performing SENSE -> THINK -> ACT loop.
 *
 * - Uses Perception.perceive for safe screen reads.
 * - Uses GeminiApi for thinking; handles conversational/text-only responses by stopping the loop.
 * - Uses ActionExecutor for safely executing UI actions with max-failure protections.
 *
 * The loop is defensive: every external call is wrapped to avoid unhandled exceptions and to ensure
 * immediate stop if Gemini indicates a direct response (conversation) or if repeated failures occur.
 */

class Agent(private val service: AccessibilityService, private val externalScope: CoroutineScope?) {
    private val TAG = "Agent"
    private var loopJob: Job? = null
    private val executor = ActionExecutor(service)
    private val api = GeminiApi(apiKey = null, apiUrl = "https://api.example.com/v1/gemini") // replace with real config
    private val pollingDelayMs = 800L

    @Volatile
    var state = AgentState(stopped = false)

    fun start() {
        if (loopJob != null && loopJob?.isActive == true) {
            Log.i(TAG, "Agent already running")
            return
        }
        val scope = externalScope ?: kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        loopJob = scope.launch {
            runLoop()
        }
    }

    fun stop() {
        state.stopped = true
        loopJob?.cancel()
    }

    private suspend fun runLoop() {
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Agent loop started")
            var failures = 0
            while (!state.stopped) {
                try {
                    // SENSE
                    val screen = try {
                        perceive(service)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Perception error: ${t.localizedMessage}")
                        ScreenAnalysis()
                    }

                    // THINK
                    val prompt = buildPromptFromScreen(screen)
                    val llmText = try {
                        api.queryTextResponse(prompt, screen)
                    } catch (t: Throwable) {
                        Log.w(TAG, "LLM call error: ${t.localizedMessage}")
                        null
                    }

                    if (!llmText.isNullOrBlank()) {
                        // If the response appears conversational/direct, return to caller and stop loop.
                        if (looksConversational(llmText)) {
                            // deliver immediate response (log for now; integrate TTS or UI as needed)
                            Log.i(TAG, "LLM Conversational Response: $llmText")
                            state.stopped = true
                            break
                        }

                        // Otherwise parse as actions (very defensive parsing)
                        val actions = parseActionsSafely(llmText)
                        var actionFailure = false
                        for (act in actions) {
                            val ok = executor.execute(act)
                            if (!ok) {
                                actionFailure = true
                                break
                            }
                            if (state.stopped) break
                        }
                        if (actionFailure) {
                            failures++
                        } else {
                            failures = 0
                        }
                    } else {
                        // LLM returned nothing; consider this a benign skip
                        failures++
                    }

                    // Stop on repeated failures to avoid infinite loops
                    if (failures >= 6) {
                        Log.w(TAG, "Stopping agent after repeated failures")
                        state.stopped = true
                        break
                    }

                } catch (t: Throwable) {
                    Log.w(TAG, "Agent loop exception: ${t.localizedMessage}")
                } finally {
                    // small delay between iterations to avoid busy loops
                    delay(pollingDelayMs)
                }
            }
            Log.i(TAG, "Agent loop finished")
        }
    }

    private fun buildPromptFromScreen(screen: ScreenAnalysis): String {
        // Keep prompt simple. Avoid passing complex structures that could be null.
        return "ScreenTitle: ${screen.title}\nScreenSummary: ${screen.textSummary}\nPlease provide actions or conversational answer."
    }

    private fun looksConversational(text: String): Boolean {
        // Heuristic: if LLM responds like a paragraph / question answer rather than action list, treat it as conversational.
        val lowered = text.trim().lowercase()
        if (lowered.length < 200 && (lowered.startsWith("sure") || lowered.startsWith("i can") || (lowered.contains("here") && lowered.contains("you")))) {
            return true
        }
        // If text contains plain sentences and no 'click', 'tap', 'type', treat as conversational
        val containsActionKeywords = listOf("click", "tap", "type", "scroll", "press", "input", "enter")
            .any { lowered.contains(it) }
        return !containsActionKeywords
    }

    private fun parseActionsSafely(text: String): List<ScreenAction> {
        // Very defensive, minimal parser: lines starting with ACTION:...
        return try {
            val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
            val actions = mutableListOf<ScreenAction>()
            for (line in lines) {
                val lower = line.lowercase()
                when {
                    lower.startsWith("click:") -> {
                        val arg = line.substringAfter(":", "").trim()
                        if (arg.isNotEmpty()) actions.add(ScreenAction.ClickByText(arg))
                    }
                    lower.startsWith("tap:") -> {
                        val arg = line.substringAfter(":", "").trim()
                        if (arg.isNotEmpty()) actions.add(ScreenAction.ClickByText(arg))
                    }
                    lower.startsWith("type:") -> {
                        val arg = line.substringAfter(":", "").trim()
                        // type: viewId|text  OR type: text (fallback)
                        val parts = arg.split("|", limit = 2)
                        val viewId = parts.getOrNull(0)?.takeIf { it.contains("/") } // heuristic
                        val txt = parts.getOrNull(1) ?: parts.getOrNull(0) ?: ""
                        actions.add(ScreenAction.TypeText(viewId, txt))
                    }
                    lower.startsWith("scroll:") -> {
                        val arg = line.substringAfter(":", "").trim()
                        if (arg.isNotEmpty()) actions.add(ScreenAction.ScrollByText(arg))
                    }
                    lower.startsWith("done") || lower.startsWith("finish") -> {
                        actions.add(ScreenAction.Done)
                    }
                }
            }
            if (actions.isEmpty()) {
                // If nothing parseable, attempt a default conversational fallback: stop.
                listOf(ScreenAction.Done)
            } else actions
        } catch (t: Throwable) {
            Log.w(TAG, "parseActionsSafely failed: ${t.localizedMessage}")
            listOf(ScreenAction.Done)
        }
    }

    /**
     * Process speech recognized by the on-device recognizer. This method drives the THINK step
     * immediately: it queries the LLM/local engine and either speaks a conversational answer
     * (and stops the agent loop) or parses and executes actions via the ActionExecutor.
     */
    fun processUserSpeech(userText: String) {
        try {
            val scopeToUse = externalScope ?: CoroutineScope(Dispatchers.Default)
            scopeToUse.launch {
                try {
                    val screen = try { perceive(service) } catch (t: Throwable) { Log.w(TAG, "perceive failed: ${t.localizedMessage}"); ScreenAnalysis() }
                    val prompt = "UserSpeech: ${userText.trim()}\nScreenTitle: ${screen.title}\nScreenSummary: ${screen.textSummary}\nPlease provide a direct answer or a list of actions."
                    val response = try { api.queryTextResponse(prompt, screen) } catch (t: Throwable) { Log.w(TAG, "LLM during processUserSpeech failed: ${t.localizedMessage}"); null }

                    if (!response.isNullOrBlank()) {
                        if (looksConversational(response)) {
                            Log.i(TAG, "Conversational response: $response")
                            state.stopped = true
                            // Speak and show using the service overlay/tts
                            try {
                                (service as? AgentService)?.speakAndShow(response)
                            } catch (t: Throwable) {
                                Log.w(TAG, "speakAndShow failed: ${t.localizedMessage}")
                            }
                        } else {
                            // Non-conversational: parse actions and execute
                            val actions = parseActionsSafely(response)
                            for (act in actions) {
                                val ok = executor.execute(act)
                                if (!ok) break
                                if (state.stopped) break
                            }
                        }
                    } else {
                        Log.w(TAG, "Empty response from LLM for user speech")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "processUserSpeech error: ${t.localizedMessage}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "processUserSpeech launch failed: ${t.localizedMessage}")
        }
    }
}

data class AgentState(var stopped: Boolean = false)
