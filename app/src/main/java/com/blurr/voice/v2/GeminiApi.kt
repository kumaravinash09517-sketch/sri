package com.blurr.voice.v2

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Simple, safe HTTP client for interacting with an LLM endpoint (Gemini-like).
 *
 * This is a minimal, robust implementation with explicit timeouts and safe JSON parsing.
 * Replace API_URL and API_KEY with actual credentials in production.
 */
class GeminiApi(
    private val apiKey: String?,
    private val apiUrl: String = "https://api.example.com/v1/gemini" // placeholder - replace in prod
) {
    private val TAG = "GeminiApi"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun queryTextResponse(prompt: String, screenContext: ScreenAnalysis?): String? {
        return withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("prompt", prompt)
                    put("screen", JSONObject().apply {
                        put("title", screenContext?.title ?: "")
                        put("summary", screenContext?.textSummary ?: "")
                        put("nodeCount", screenContext?.nodeCount ?: 0)
                    })
                    put("max_tokens", 512)
                }
                val body = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), payload.toString())
                val reqBuilder = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .header("Content-Type", "application/json")

                apiKey?.let { reqBuilder.header("Authorization", "Bearer $it") }

                val request = reqBuilder.build()

                // Defend against long waits
                val respStr = withTimeoutOrNull(20_000L) {
                    client.newCall(request).execute().use { resp ->
                        resp.body?.string()
                    }
                } ?: run {
                    Log.w(TAG, "GeminiApi: request timed out")
                    null
                }

                if (respStr.isNullOrBlank()) {
                    return@withContext null
                }

                // Safe JSON parsing
                val json = try {
                    JSONObject(respStr)
                } catch (t: Throwable) {
                    Log.w(TAG, "GeminiApi: response not JSON: ${t.localizedMessage}")
                    null
                }

                // Adapt to many LLM response shapes; prefer plain text, then choices[0].text or .content
                val text = json?.optString("text", null)
                    ?: json?.optJSONArray("choices")?.optJSONObject(0)?.optString("text", null)
                    ?: json?.optJSONObject("output")?.optString("content", null)
                    ?: json?.optString("response", null)

                text?.takeIf { it.isNotBlank() }?.trim()
            } catch (t: Throwable) {
                Log.w(TAG, "GeminiApi query failed: ${t.localizedMessage}")
                null
            }
        }
    }
}
