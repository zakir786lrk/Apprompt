package com.germanium.shortcuts

import android.content.Context
import com.germanium.shortcuts.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class GeminiModel(
    val id: String,
    val displayName: String,
    val description: String
)

class GeminiService(private val context: Context) {
    private val prefs = context.getSharedPreferences("gemini_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var selectedModel: String
        get() = prefs.getString("selected_model", "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString("selected_model", value.trim()).apply()

    suspend fun fetchAvailableModels(): Result<List<GeminiModel>> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) {
            return@withContext Result.success(fallbackModels())
        }

        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$key")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/json")
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $status"
                return@withContext Result.failure(Exception("Failed to load Gemini models: $errorMsg"))
            }

            val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(responseText)
            val modelsArray = json.optJSONArray("models") ?: JSONArray()
            val list = mutableListOf<GeminiModel>()

            for (i in 0 until modelsArray.length()) {
                val obj = modelsArray.getJSONObject(i)
                val rawName = obj.optString("name", "")
                val cleanId = rawName.removePrefix("models/")
                val displayName = obj.optString("displayName", cleanId)
                val description = obj.optString("description", "")
                val methods = obj.optJSONArray("supportedGenerationMethods")
                val supportsGenerate = if (methods != null) {
                    (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                } else false

                if (supportsGenerate && cleanId.contains("gemini", ignoreCase = true)) {
                    list.add(GeminiModel(cleanId, displayName, description))
                }
            }

            // Sort: flash & pro models at the top
            val sorted = list.sortedWith(compareByDescending<GeminiModel> {
                when {
                    it.id.contains("2.5-flash") -> 100
                    it.id.contains("2.5-pro") -> 90
                    it.id.contains("1.5-flash") -> 80
                    it.id.contains("1.5-pro") -> 70
                    it.id.contains("flash") -> 50
                    else -> 10
                }
            }.thenBy { it.displayName })

            if (sorted.isNotEmpty()) {
                Result.success(sorted)
            } else {
                Result.success(fallbackModels())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateApplet(userPrompt: String): Result<Shortcut> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isBlank()) {
            return@withContext Result.failure(Exception("Please enter your Gemini API Key first."))
        }

        val model = selectedModel.ifBlank { "gemini-2.5-flash" }

        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            val systemPrompt = buildSystemPrompt()
            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", userPrompt)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.2)
                })
            }

            conn.outputStream.bufferedWriter().use {
                it.write(requestJson.toString())
                it.flush()
            }

            val status = conn.responseCode
            if (status !in 200..299) {
                val errorMsg = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: "HTTP $status"
                return@withContext Result.failure(Exception("Gemini generation failed: $errorMsg"))
            }

            val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val responseJson = JSONObject(responseText)
            val candidates = responseJson.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return@withContext Result.failure(Exception("No content generated by Gemini."))
            }

            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            val outputText = parts?.optJSONObject(0)?.optString("text").orEmpty()

            // Extract XML from output
            val extractedXml = extractXml(outputText)
                ?: return@withContext Result.failure(Exception("Gemini response did not contain valid Applet XML:\n$outputText"))

            val shortcut = parseAndSanitizeShortcut(extractedXml, userPrompt)
            Result.success(shortcut)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractXml(text: String): String? {
        val startTag = "<shortcut"
        val endTag = "</shortcut>"
        val startIndex = text.indexOf(startTag)
        val endIndex = text.lastIndexOf(endTag)
        if (startIndex != -1 && endIndex != -1 && endIndex > startIndex) {
            val content = text.substring(startIndex, endIndex + endTag.length)
            return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n$content"
        }
        return null
    }

    private fun parseAndSanitizeShortcut(xml: String, originalPrompt: String): Shortcut {
        val codec = XmlCodec()
        val decoded = try {
            codec.decode(xml.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Attempt auto-recovery
            val sanitized = ensureValidXmlAttributes(xml)
            codec.decode(sanitized.toByteArray(Charsets.UTF_8))
        }

        val uniqueId = "applet_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val cleanUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val safeIcon = if (decoded.icon in ShortcutStyles.icons.keys) decoded.icon else "bolt"
        val safeColor = if (decoded.color in ShortcutStyles.colors.keys || decoded.color.startsWith("#")) decoded.color else "blue"
        val safeName = decoded.name.ifBlank {
            originalPrompt.take(40).trim()
        }

        return decoded.copy(
            id = uniqueId,
            uuid = cleanUuid,
            name = safeName,
            icon = safeIcon,
            color = safeColor,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun ensureValidXmlAttributes(xml: String): String {
        var res = xml
        if (!res.contains("version=\"")) {
            res = res.replace("<shortcut", "<shortcut version=\"1\"")
        }
        if (!res.contains("id=\"")) {
            res = res.replace("<shortcut", "<shortcut id=\"temp_${UUID.randomUUID()}\"")
        }
        if (!res.contains("uuid=\"")) {
            res = res.replace("<shortcut", "<shortcut uuid=\"${UUID.randomUUID()}\"")
        }
        if (!res.contains("createdAt=\"")) {
            res = res.replace("<shortcut", "<shortcut createdAt=\"${System.currentTimeMillis()}\"")
        }
        if (!res.contains("updatedAt=\"")) {
            res = res.replace("<shortcut", "<shortcut updatedAt=\"${System.currentTimeMillis()}\"")
        }
        if (!res.contains("appVersion=\"")) {
            res = res.replace("<shortcut", "<shortcut appVersion=\"$SHORTCUTS_VERSION\"")
        }
        return res
    }

    private fun buildSystemPrompt(): String = """
You are Apprompt, an expert AI compiler that translates user natural language requests into native Android Applet workflows.
You must output ONLY valid XML for the applet, without Markdown fences or conversational text outside the XML.

XML Schema Specification:
<?xml version="1.0" encoding="UTF-8"?>
<shortcut version="1" id="applet_id" createdAt="0" updatedAt="0" icon="bolt" color="blue" folder="" uuid="00000000-0000-0000-0000-000000000000" appVersion="10005">
  <name>Applet Title</name>
  <steps>
    <action id="step1" type="text" enabled="true">
      <parameter key="text">Hello World</parameter>
    </action>
    <action id="step2" type="show_result" enabled="true">
      <parameter key="content">{{@step1}}</parameter>
    </action>
  </steps>
</shortcut>

Icons available (pick the most relevant):
bolt, layers, star, heart, music, folder, calendar, camera, chat, globe, code, book, coffee, home, person, check.

Colors available:
blue, purple, green, orange, yellow, teal, pink, red.

Common Action Types and Parameters:
1. Interactive & Results:
   - type="ask_input": popup prompt to ask user text/number. Parameters: "title", "prompt", "resultVariable"
   - type="show_result": shows dialog popup. Parameters: "title", "content"
   - type="quick_look": preview image, file, or text. Parameters: "content"
   - type="notification": Android notification. Parameters: "title", "text"
2. Text & Processing:
   - type="text": holds string literal or variable interpolation. Parameters: "text"
   - type="get_text_from_input": extract text from clipboard/file.
   - type="replace_text": Parameters: "target", "replacement", "source"
   - type="regex_replace": Parameters: "pattern", "replacement", "source"
   - type="split_text": Parameters: "delimiter", "source"
   - type="join_text": Parameters: "separator", "source"
3. Math & Logic:
   - type="number": Parameters: "value"
   - type="calculate": Parameters: "left", "operation" (+,-,*,/), "right"
   - type="calculate_expression": Parameters: "expression" (e.g. "sin(45) + 2^3")
   - type="random_number": Parameters: "minimum", "maximum"
4. Device & System:
   - type="get_clipboard", type="set_clipboard": Parameters: "text"
   - type="speak_text": TTS voice output. Parameters: "text"
   - type="vibrate": Parameters: "duration"
   - type="battery_level": returns integer 0-100.
   - type="device_info": returns device details.
   - type="current_date": Parameters: "format"
   - type="date_difference": Parameters: "firstDate", "secondDate", "unit"
5. Media & Files:
   - type="take_photo": opens camera
   - type="get_image": picks image
   - type="extract_text_from_image": on-device OCR from image
   - type="generate_qr": generates QR code PNG from text. Parameters: "content"
   - type="read_barcode": scans QR/barcode from image
   - type="write_file": Parameters: "path", "content"
   - type="read_file": Parameters: "path"
   - type="choose_file": opens file picker
6. Web:
   - type="url": Parameters: "url"
   - type="open_url": opens in browser. Parameters: "url"
   - type="get_url": HTTP request. Parameters: "url", "method" (GET, POST), "body"
   - type="share": opens Android share sheet. Parameters: "content"

Variable references:
To reference the output of step id "step1", write {{@step1}} in a parameter value.
Keep workflows functional, intuitive, and safe.
Output ONLY the XML.
""".trimIndent()

    companion object {
        fun fallbackModels(): List<GeminiModel> = listOf(
            GeminiModel("gemini-2.5-flash", "Gemini 2.5 Flash", "Next-gen fast and versatile multimodal model (Recommended)"),
            GeminiModel("gemini-2.5-pro", "Gemini 2.5 Pro", "Advanced reasoning model for complex workflows"),
            GeminiModel("gemini-1.5-flash", "Gemini 1.5 Flash", "Lightweight and ultra-fast model"),
            GeminiModel("gemini-1.5-pro", "Gemini 1.5 Pro", "High-capacity multimodal reasoning model")
        )
    }
}
