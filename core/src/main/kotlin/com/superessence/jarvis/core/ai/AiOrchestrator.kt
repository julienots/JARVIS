package com.superessence.jarvis.core.ai

/**
 * Interroge les fournisseurs configurés par ordre de priorité.
 * Si l'un échoue (quota, réseau, clé), le suivant prend le relais automatiquement.
 */
class AiOrchestrator(
    private val http: HttpClient,
    private val configs: () -> List<AiProviderConfig>,
    private val timeoutMs: Int = 25_000,
    private val retryDelayMs: Long = 1_500,
) {
    fun hasUsableProvider() = configs().any { it.isUsable }

    @Throws(AiException::class)
    fun ask(system: String, history: List<ChatMessage>): AiAnswer {
        val usable = configs().filter { it.isUsable }
        if (usable.isEmpty()) throw AiException(AiErrorKind.NOT_CONFIGURED, null)
        var last: AiException? = null
        for (c in usable) {
            try {
                return askOne(c, system, history)
            } catch (e: AiException) {
                last = e
            }
        }
        throw last ?: AiException(AiErrorKind.NOT_CONFIGURED, null)
    }

    @Throws(AiException::class)
    fun askOne(config: AiProviderConfig, system: String, history: List<ChatMessage>): AiAnswer {
        val req = AiProtocol.buildRequest(config, system, history)
        var res = send(config, req)
        // Limite par seconde (Mistral gratuit…) : une seule nouvelle tentative. Un quota épuisé ne se débloque pas ainsi.
        if (res.code == 429 && !res.body.contains("quota", ignoreCase = true) && retryDelayMs > 0) {
            Thread.sleep(retryDelayMs)
            res = send(config, req)
        }
        return AiAnswer(AiProtocol.parseResponse(config.type, res), config.type, config.model)
    }

    /** Modèles de conversation disponibles pour cette clé. */
    @Throws(AiException::class)
    fun listModels(config: AiProviderConfig): List<String> {
        val req = AiProtocol.buildModelsRequest(config)
        val res = try {
            http.get(req.url, req.headers, timeoutMs)
        } catch (e: java.io.IOException) {
            throw AiException(AiErrorKind.NETWORK, config.type, e.message)
        }
        return AiProtocol.parseModels(config.type, res)
    }

    private fun send(config: AiProviderConfig, req: HttpRequestSpec): HttpResult = try {
        http.post(req, timeoutMs)
    } catch (e: java.io.IOException) {
        throw AiException(AiErrorKind.NETWORK, config.type, e.message)
    }
}
