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
