package com.superessence.jarvis.core

import com.superessence.jarvis.core.ai.AiActionExtractor
import com.superessence.jarvis.core.ai.AiErrorKind
import com.superessence.jarvis.core.ai.AiException
import com.superessence.jarvis.core.ai.AiOrchestrator
import com.superessence.jarvis.core.ai.AiProtocol
import com.superessence.jarvis.core.ai.AiProviderConfig
import com.superessence.jarvis.core.ai.AiProviderType
import com.superessence.jarvis.core.ai.ChatMessage
import com.superessence.jarvis.core.ai.HttpResult
import com.superessence.jarvis.core.ai.Role
import com.superessence.jarvis.core.intent.JarvisIntent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiProtocolTest {
    private val hist = listOf(ChatMessage(Role.USER, "Bonjour"))

    private fun cfg(t: AiProviderType) = AiProviderConfig(t, apiKey = "cle-test", enabled = true)

    @Test fun geminiRequest() {
        val r = AiProtocol.buildRequest(cfg(AiProviderType.GEMINI).copy(webGrounding = true), "sys", hist)
        assertTrue(r.url.endsWith("/models/gemini-flash-latest:generateContent"))
        assertEquals("cle-test", r.headers["x-goog-api-key"])
        val b = JSONObject(r.body)
        assertEquals("sys", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("user", b.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertTrue(b.has("tools"))
        assertFalse(r.url.contains("cle-test")) // la clé ne doit pas apparaître dans l'URL
    }

    @Test fun claudeRequest() {
        val r = AiProtocol.buildRequest(cfg(AiProviderType.CLAUDE), "sys", hist)
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        assertEquals("cle-test", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        val b = JSONObject(r.body)
        assertEquals("sys", b.getString("system"))
        assertEquals(AiProtocol.MAX_TOKENS, b.getInt("max_tokens"))
    }

    @Test fun openAiCompatibleRequests() {
        for ((t, url) in listOf(
            AiProviderType.OPENAI to "https://api.openai.com/v1/chat/completions",
            AiProviderType.GROQ to "https://api.groq.com/openai/v1/chat/completions",
            AiProviderType.MISTRAL to "https://api.mistral.ai/v1/chat/completions",
        )) {
            val r = AiProtocol.buildRequest(cfg(t), "sys", hist)
            assertEquals(url, r.url)
            assertEquals("Bearer cle-test", r.headers["Authorization"])
            val msgs = JSONObject(r.body).getJSONArray("messages")
            assertEquals("system", msgs.getJSONObject(0).getString("role"))
            assertEquals("user", msgs.getJSONObject(1).getString("role"))
        }
        val custom = AiProtocol.buildRequest(AiProviderConfig(AiProviderType.CUSTOM, model = "llama3", baseUrl = "http://10.0.0.2:11434/v1/", enabled = true), "s", hist)
        assertEquals("http://10.0.0.2:11434/v1/chat/completions", custom.url)
        assertFalse(custom.headers.containsKey("Authorization"))
    }

    @Test fun parseResponses() {
        assertEquals("Salut", AiProtocol.parseResponse(AiProviderType.GEMINI, HttpResult(200,
            """{"candidates":[{"content":{"parts":[{"text":"Salut"}]}}]}""")))
        assertEquals("Salut", AiProtocol.parseResponse(AiProviderType.CLAUDE, HttpResult(200,
            """{"content":[{"type":"text","text":"Salut"}]}""")))
        assertEquals("Salut", AiProtocol.parseResponse(AiProviderType.GROQ, HttpResult(200,
            """{"choices":[{"message":{"role":"assistant","content":"Salut"}}]}""")))
    }

    @Test fun httpErrorsAreTyped() {
        val cases = mapOf(401 to AiErrorKind.AUTH, 429 to AiErrorKind.RATE_LIMIT, 503 to AiErrorKind.SERVER, 404 to AiErrorKind.BAD_REQUEST)
        for ((code, kind) in cases) {
            try {
                AiProtocol.parseResponse(AiProviderType.OPENAI, HttpResult(code, """{"error":{"message":"x"}}"""))
                fail()
            } catch (e: AiException) { assertEquals(kind, e.kind) }
        }
    }

    @Test fun sanitizeHistory() {
        val s = AiProtocol.sanitize(listOf(
            ChatMessage(Role.ASSISTANT, "orphelin"), ChatMessage(Role.USER, "a"), ChatMessage(Role.USER, "b"),
            ChatMessage(Role.ASSISTANT, "c"), ChatMessage(Role.USER, ""), ChatMessage(Role.USER, "d"),
        ))
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), s.map { it.role })
        assertEquals("a\nb", s[0].text)
    }

    @Test fun orchestratorFallsBackToNextProvider() {
        val calls = mutableListOf<String>()
        val orch = AiOrchestrator({ req, _ ->
            calls += req.url
            if (req.url.contains("groq")) HttpResult(429, "{}")
            else HttpResult(200, """{"choices":[{"message":{"content":"OK Mistral"}}]}""")
        }, { listOf(cfg(AiProviderType.GROQ), cfg(AiProviderType.MISTRAL)) })
        val a = orch.ask("s", hist)
        assertEquals("OK Mistral", a.text)
        assertEquals(AiProviderType.MISTRAL, a.provider)
        assertEquals(2, calls.size)
    }

    @Test fun orchestratorNetworkErrorAndNoConfig() {
        val orch = AiOrchestrator({ _, _ -> throw java.io.IOException("offline") }, { listOf(cfg(AiProviderType.CLAUDE)) })
        try { orch.ask("s", hist); fail() } catch (e: AiException) { assertEquals(AiErrorKind.NETWORK, e.kind) }
        val none = AiOrchestrator({ _, _ -> HttpResult(200, "") }, { listOf(AiProviderConfig(AiProviderType.CLAUDE)) })
        assertFalse(none.hasUsableProvider())
        try { none.ask("s", hist); fail() } catch (e: AiException) { assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind) }
    }

    @Test fun actionExtraction() {
        val e = AiActionExtractor.extract("Je lance Spotify. [[ACTION {\"type\":\"open_app\",\"app\":\"Spotify\"}]]")
        assertEquals("Je lance Spotify.", e.speech)
        assertEquals(JarvisIntent.OpenApp("Spotify"), e.intent)
        val bad = AiActionExtractor.extract("Ok [[ACTION {\"type\":\"format_disk\"}]]")
        assertEquals(null, bad.intent)
        assertEquals("Ok", bad.speech)
    }
}
