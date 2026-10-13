package com.superessence.jarvis.core.ai

/**
 * Interroge les fournisseurs configurés par ordre de priorité.
 * Si l'un échoue (quota, réseau, clé), le suivant prend le relais automatiquement.
 */
class AiOrchestrator(
    private val http: HttpClient,
    private val configs: () -> List<AiProviderConfig>,
    private val timeoutMs: Int = 25_000,
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
        val res = try {
            http.post(req, timeoutMs)
        } catch (e: java.io.IOException) {
            throw AiException(AiErrorKind.NETWORK, config.type, e.message)
        }
        return AiAnswer(AiProtocol.parseResponse(config.type, res), config.type, config.model)
    }
}
