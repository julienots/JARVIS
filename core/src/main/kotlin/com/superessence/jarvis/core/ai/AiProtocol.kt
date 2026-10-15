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
                // Les modèles gpt-oss raisonnent avant de répondre : on limite ce raisonnement pour garder des tokens à la réponse.
                if (config.type == AiProviderType.GROQ && config.model.contains("gpt-oss")) body.put("reasoning_effort", "low")
                val headers = mutableMapOf("Content-Type" to "application/json")
                if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey.trim()}"
                if (config.type == AiProviderType.CUSTOM) headers["X-Title"] = "Jarvis"
                HttpRequestSpec("$base/chat/completions", headers, body.toString())
            }
        }
    }

    /** Requête GET listant les modèles accessibles avec la clé. */
    fun buildModelsRequest(config: AiProviderConfig): HttpRequestSpec {
        val base = config.baseUrl.trimEnd('/')
        val key = config.apiKey.trim()
        return when (config.type) {
            AiProviderType.GEMINI -> HttpRequestSpec("$base/models?pageSize=1000", mapOf("x-goog-api-key" to key), "")
            AiProviderType.CLAUDE -> HttpRequestSpec("$base/models?limit=100", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), "")
            else -> HttpRequestSpec("$base/models", if (key.isNotBlank()) mapOf("Authorization" to "Bearer $key") else emptyMap(), "")
        }
    }

    private val NON_CHAT = listOf("embed", "tts", "whisper", "transcribe", "audio", "image", "imagen", "veo", "dall-e",
        "moderation", "guard", "realtime", "aqa", "live", "computer-use", "robotics", "search")

    /** Liste triée des modèles de conversation, ou [AiException] si la requête a échoué. */
    fun parseModels(type: AiProviderType, result: HttpResult): List<String> {
        if (result.code !in 200..299) throw AiException(errorKind(result.code), type, extractError(result.body))
        val json = try { JSONObject(result.body) } catch (e: Exception) {
            throw AiException(AiErrorKind.BAD_RESPONSE, type, e.message)
        }
        val ids = if (type == AiProviderType.GEMINI) {
            val arr = json.optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                }
                .map { it.optString("name").removePrefix("models/") }
        } else {
            val arr = json.optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        }
        return ids.filter { id -> id.isNotBlank() && NON_CHAT.none { id.lowercase().contains(it) } }.distinct().sorted()
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

    fun errorKind(code: Int): AiErrorKind = when (code) {
        401, 403 -> AiErrorKind.AUTH
        429 -> AiErrorKind.RATE_LIMIT
        400, 404, 422 -> AiErrorKind.BAD_REQUEST
        in 500..599 -> AiErrorKind.SERVER
        else -> AiErrorKind.BAD_RESPONSE
    }

    fun extractError(body: String): String? = try {
        val j = JSONObject(body)
        val e = j.opt("error")
        val message = when (e) {
            is JSONObject -> e.optString("message").ifBlank { null }
            is String -> e
            else -> j.optString("message").ifBlank { null }
        }
        // Google détaille les quotas dans error.details : on les met en tête car le message est souvent tronqué.
        val quota = (e as? JSONObject)?.let { quotaSummary(it) }
        listOfNotNull(quota, message).joinToString(" — ").ifBlank { null }?.take(240)
    } catch (_: Exception) { body.take(120).ifBlank { null } }

    private fun quotaSummary(error: JSONObject): String? {
        val details = error.optJSONArray("details") ?: return null
        var limit: String? = null
        var retry: String? = null
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i) ?: continue
            val t = d.optString("@type")
            if (t.endsWith("QuotaFailure")) {
                val v = d.optJSONArray("violations")?.optJSONObject(0) ?: continue
                val id = v.optString("quotaId").ifBlank { v.optString("quotaMetric") }
                limit = when {
                    id.contains("PerDay", ignoreCase = true) -> "quota du jour épuisé"
                    id.contains("PerMinute", ignoreCase = true) -> "limite par minute atteinte"
                    else -> "quota épuisé"
                } + if (id.contains("FreeTier", ignoreCase = true)) " (offre gratuite)" else ""
            } else if (t.endsWith("RetryInfo")) {
                retry = d.optString("retryDelay").ifBlank { null }?.let { "réessayer dans ${it.substringBefore('.').removeSuffix("s")} s" }
            }
        }
        return listOfNotNull(limit, retry).joinToString(", ").ifBlank { null }
    }

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
