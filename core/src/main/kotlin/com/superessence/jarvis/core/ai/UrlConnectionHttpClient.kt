package com.superessence.jarvis.core.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Client HTTP minimal basé sur HttpURLConnection (disponible sur Android et la JVM). */
class UrlConnectionHttpClient : HttpClient {

    override fun post(request: HttpRequestSpec, timeoutMs: Int): HttpResult =
        execute("POST", request.url, request.headers, request.body, timeoutMs)

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult =
        execute("GET", url, headers, null, timeoutMs)

    private fun execute(method: String, url: String, headers: Map<String, String>, body: String?, timeoutMs: Int): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs.coerceAtMost(15_000)
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Jarvis-Android/1.0 (SuperEssence)")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResult(code, text)
        } catch (e: IOException) {
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
