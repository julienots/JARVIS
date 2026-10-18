package com.superessence.jarvis.core.ai

import org.json.JSONArray
import org.json.JSONObject

/** Construit les requêtes HTTP propres à chaque fournisseur et lit leurs réponses. */
object AiProtocol {

    const val MAX_TOKENS = 700

    fun buildRequest(config: AiProviderConfig, system: String, history: List<ChatMessage>): HttpRequestSpec {
        val messages = sanitize(history)
        val base = config.baseUrl.trimEnd('/')
        return when (config.type) {
            AiProviderType.GEMINI -> {
                val contents = JSONArray()
                messages.forEach { m ->
                    contents.put(JSONObject()
                        .put("role", if (m.role == Role.USER) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
                }
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", contents)
                    .put("generationConfig", JSONObject().put("maxOutputTokens", MAX_TOKENS).put("temperature", 0.7))
                if (config.webGrounding) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                HttpRequestSpec(
                    "$base/models/${config.model.trim()}:generateContent",
                    mapOf("Content-Type" to "application/json", "x-goog-api-key" to config.apiKey.trim()),
                    body.toString(),
                )
            }
            AiProviderType.CLAUDE -> {
                val arr = JSONArray()
                messages.forEach { m ->
                    arr.put(JSONObject().put("role", if (m.role == Role.USER) "user" else "assistant").put("content", m.text))
                }
                val body = JSONObject()
                    .put("model", config.model.trim())
                    .put("max_tokens", MAX_TOKENS)
                    .put("system", system)
                    .put("messages", arr)
                HttpRequestSpec(
                    "$base/messages",
                    mapOf(
                        "Content-Type" to "application/json",
                        "x-api-key" to config.apiKey.trim(),
                        "anthropic-version" to "2023-06-01",
                    ),
                    body.toString(),
                )
            }
            else -> {
                val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
                messages.forEach { m ->
                    arr.put(JSONObject().put("role", if (m.role == Role.USER) "user" else "assistant").put("content", m.text))
                }
                val body = JSONObject().put("model", config.model.trim()).put("messages", arr)
                when (config.type) {
                    // Les modèles OpenAI récents refusent max_tokens et les températures personnalisées.
                    AiProviderType.OPENAI, AiProviderType.GROQ -> body.put("max_completion_tokens", MAX_TOKENS)
                    else -> body.put("max_tokens", MAX_TOKENS).put("temperature", 0.7)
                }
                val headers = mutableMapOf("Content-Type" to "application/json")
                if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey.trim()}"
                if (config.type == AiProviderType.CUSTOM) headers["X-Title"] = "Jarvis"
                HttpRequestSpec("$base/chat/completions", headers, body.toString())
            }
        }
    }

    /** Extrait le texte de la réponse ou lève une [AiException] typée. */
    fun parseResponse(type: AiProviderType, result: HttpResult): String {
        if (result.code !in 200..299) throw AiException(errorKind(result.code), type, extractError(result.body))
        val json = try { JSONObject(result.body) } catch (e: Exception) {
            throw AiException(AiErrorKind.BAD_RESPONSE, type, e.message)
        }
        val text = when (type) {
            AiProviderType.GEMINI -> {
                val cands = json.optJSONArray("candidates")
                if (cands == null || cands.length() == 0) {
                    val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason")
                    throw AiException(AiErrorKind.BAD_RESPONSE, type, blocked?.takeIf { it.isNotBlank() } ?: "aucune réponse")
                }
                val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            }
            AiProviderType.CLAUDE -> {
                val content = json.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).map { content.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("") { it.optString("text") }
            }
            else -> {
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) throw AiException(AiErrorKind.BAD_RESPONSE, type, "aucun choix")
                val msg = choices.getJSONObject(0).optJSONObject("message")
                val c = msg?.opt("content")
                when (c) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text") ?: "" }
                    else -> ""
                }
            }
        }
        if (text.isBlank()) throw AiException(AiErrorKind.BAD_RESPONSE, type, "réponse vide")
        return text.trim()
    }

    // ---------- Découverte automatique des modèles ----------

    /** Requête GET qui liste les modèles accessibles avec cette clé. */
    fun modelsRequest(config: AiProviderConfig): Pair<String, Map<String, String>> {
        val base = config.baseUrl.trimEnd('/')
        val key = config.apiKey.trim()
        return when (config.type) {
            AiProviderType.GEMINI -> "$base/models?pageSize=1000" to mapOf("x-goog-api-key" to key)
            AiProviderType.CLAUDE -> "$base/models?limit=100" to mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")
            else -> "$base/models" to (if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap())
        }
    }

    /** Noms des modèles de discussion présents dans la réponse de [modelsRequest]. */
    fun parseModels(type: AiProviderType, body: String): List<String> {
        val json = try { JSONObject(body) } catch (_: Exception) { return emptyList() }
        return if (type == AiProviderType.GEMINI) {
            val arr = json.optJSONArray("models") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods")
                    methods == null || (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                }
                .map { it.optString("name").removePrefix("models/") }.filter { it.isNotBlank() }
        } else {
            val arr = json.optJSONArray("data") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optBoolean("active", true) }
                .map { it.optString("id") }.filter { it.isNotBlank() }
        }
    }

    private val notChat = Regex("embed|whisper|tts|audio|speech|transcri|realtime|image|imagen|veo|vision-preview|dall|moderation|guard|" +
        "davinci|babbage|instruct|search|computer|robotics|aqa|learnlm|gemma|native|live|ocr|codestral|devstral|prompt|safeguard|compound|lyria|nano")

    /** Choisit le meilleur modèle généraliste rapide parmi [models] (sans modèle de niche ni préversion si possible). */
    fun pickModel(type: AiProviderType, models: List<String>): String? {
        val usable = models.filter { !notChat.containsMatchIn(it.lowercase()) }.ifEmpty { return null }
        // Version du modèle, sans les dates (2025-04-14) ni les tailles (70b, 120b).
        fun version(m: String) = Regex("(\\d+(?:\\.\\d+)?)")
            .findAll(m.replace(Regex("\\d{4}-?\\d{2}-?\\d{2}|\\d+(\\.\\d+)?[bkm]\\b|-\\d{3,4}\\b"), ""))
            .map { it.value.toDoubleOrNull() ?: 0.0 }.filter { it < 100 }.maxOrNull() ?: 0.0
        fun score(m: String): Double {
            val n = m.lowercase()
            var s = version(n)
            if (n.contains("preview") || n.contains("exp") || Regex("\\d{4}-?\\d{2}-?\\d{2}").containsMatchIn(n)) s -= 50
            when (type) {
                AiProviderType.GEMINI -> {
                    if (n == "gemini-flash-latest") s += 1000
                    if (n.contains("flash")) s += 100
                    if (n.contains("lite")) s -= 40
                    if (!n.startsWith("gemini")) s -= 500
                }
                AiProviderType.GROQ -> {
                    if (n.contains("versatile")) s += 120
                    if (n.contains("gpt-oss-120b")) s += 110
                    if (n.contains("llama") || n.contains("qwen") || n.contains("kimi")) s += 60
                    if (n.contains("instant") || n.contains("8b") || n.contains("20b")) s -= 30
                }
                AiProviderType.OPENAI -> {
                    if (!n.startsWith("gpt")) s -= 500
                    if (n.contains("mini")) s += 100
                    if (n.contains("nano")) s -= 20
                    if (n.contains("chat-latest") || n.contains("codex") || n.contains("oss")) s -= 200
                }
                AiProviderType.MISTRAL -> {
                    if (n == "mistral-small-latest") s += 1000
                    if (n == "mistral-medium-latest") s += 900
                    if (n.endsWith("-latest")) s += 100
                    if (n.contains("embed") || n.contains("pixtral") || n.contains("ministral")) s -= 100
                }
                AiProviderType.CLAUDE -> {
                    if (n.contains("sonnet")) s += 100
                    if (n.contains("haiku")) s += 60
                }
                AiProviderType.CUSTOM -> {}
            }
            return s
        }
        return usable.maxByOrNull { score(it) }
    }

    fun errorKind(code: Int): AiErrorKind = when (code) {
        401, 403 -> AiErrorKind.AUTH
        429 -> AiErrorKind.RATE_LIMIT
        400, 404, 422 -> AiErrorKind.BAD_REQUEST
        in 500..599 -> AiErrorKind.SERVER
        else -> AiErrorKind.BAD_RESPONSE
    }

    private fun extractError(body: String): String? = try {
        val j = JSONObject(body)
        val e = j.opt("error")
        when (e) {
            is JSONObject -> e.optString("message").ifBlank { null }
            is String -> e
            else -> j.optString("message").ifBlank { null }
        }?.take(200)
    } catch (_: Exception) { body.take(120).ifBlank { null } }

    /**
     * Les API exigent un historique qui commence par l'utilisateur et alterne les rôles.
     * On fusionne les messages consécutifs du même rôle et on retire les messages vides.
     */
    fun sanitize(history: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        for (m in history) {
            if (m.text.isBlank()) continue
            if (out.isEmpty() && m.role != Role.USER) continue
            if (out.isNotEmpty() && out.last().role == m.role) {
                out[out.size - 1] = ChatMessage(m.role, out.last().text + "\n" + m.text)
            } else out += m
        }
        while (out.isNotEmpty() && out.last().role != Role.USER) out.removeAt(out.size - 1)
        return out
    }
}
