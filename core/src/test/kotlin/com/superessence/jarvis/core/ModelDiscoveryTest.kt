package com.superessence.jarvis.core

import com.superessence.jarvis.core.ai.AiOrchestrator
import com.superessence.jarvis.core.ai.AiProtocol
import com.superessence.jarvis.core.ai.AiProviderConfig
import com.superessence.jarvis.core.ai.AiProviderType
import com.superessence.jarvis.core.ai.ChatMessage
import com.superessence.jarvis.core.ai.HttpClient
import com.superessence.jarvis.core.ai.HttpRequestSpec
import com.superessence.jarvis.core.ai.HttpResult
import com.superessence.jarvis.core.ai.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** Un modèle retiré par le fournisseur est remplacé automatiquement. */
class ModelDiscoveryTest {

    @Test fun picksGeneralPurposeModels() {
        assertEquals("gemini-flash-latest", AiProtocol.pickModel(AiProviderType.GEMINI,
            listOf("gemini-2.0-flash", "gemini-flash-latest", "gemini-embedding-001", "gemini-3.5-pro-preview", "gemma-3-27b-it")))
        assertEquals("gemini-3.5-flash", AiProtocol.pickModel(AiProviderType.GEMINI,
            listOf("gemini-2.0-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.5-flash-image", "gemini-3.6-flash-preview-09-2026")))
        assertEquals("llama-4.1-70b-versatile", AiProtocol.pickModel(AiProviderType.GROQ,
            listOf("whisper-large-v3", "llama-3.1-8b-instant", "llama-4.1-70b-versatile", "meta-llama/llama-guard-4-12b", "openai/gpt-oss-20b")))
        assertEquals("gpt-5-mini", AiProtocol.pickModel(AiProviderType.OPENAI,
            listOf("gpt-4.1-mini-2025-04-14", "gpt-5-mini", "gpt-5", "text-embedding-3-small", "gpt-4o-realtime-preview", "tts-1")))
        assertEquals("mistral-small-latest", AiProtocol.pickModel(AiProviderType.MISTRAL,
            listOf("mistral-embed", "mistral-small-latest", "codestral-latest", "mistral-large-2411")))
    }

    @Test fun parsesModelLists() {
        val gemini = """{"models":[{"name":"models/gemini-flash-latest","supportedGenerationMethods":["generateContent"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""
        assertEquals(listOf("gemini-flash-latest"), AiProtocol.parseModels(AiProviderType.GEMINI, gemini))
        val openAi = """{"data":[{"id":"llama-3.1-8b-instant","active":true},{"id":"old","active":false}]}"""
        assertEquals(listOf("llama-3.1-8b-instant"), AiProtocol.parseModels(AiProviderType.GROQ, openAi))
    }

    @Test fun retiredModelIsReplacedAndSaved() {
        val saved = mutableListOf<String>()
        val http = object : HttpClient {
            override fun post(request: HttpRequestSpec, timeoutMs: Int) =
                if (request.body.contains("llama-3.3-70b-versatile")) HttpResult(404, """{"error":{"message":"The model does not exist"}}""")
                else HttpResult(200, """{"choices":[{"message":{"content":"Bonjour"}}]}""")
            override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) =
                HttpResult(200, """{"data":[{"id":"openai/gpt-oss-120b"},{"id":"whisper-large-v3"}]}""")
        }
        val cfg = AiProviderConfig(AiProviderType.GROQ, apiKey = "k", model = "llama-3.3-70b-versatile", enabled = true)
        val orch = AiOrchestrator(http, { listOf(cfg) }, onModelChanged = { _, m -> saved += m })
        val a = orch.ask("sys", listOf(ChatMessage(Role.USER, "Salut")))
        assertEquals("Bonjour", a.text)
        assertEquals("openai/gpt-oss-120b", a.model)
        assertEquals(listOf("openai/gpt-oss-120b"), saved)
    }
}
