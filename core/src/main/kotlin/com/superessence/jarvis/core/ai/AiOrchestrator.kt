package com.superessence.jarvis.core.ai

/**
 * Interroge les fournisseurs configurés par ordre de priorité.
 * Si l'un échoue (quota, réseau, clé), le suivant prend le relais automatiquement.
 */
class AiOrchestrator(
    private val http: HttpClient,
    private val configs: () -> List<AiProviderConfig>,
    private val timeoutMs: Int = 25_000,
    /** Appelé quand un modèle périmé a été remplacé automatiquement (à enregistrer). */
    private val onModelChanged: (AiProviderType, String) -> Unit = { _, _ -> },
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

    /**
     * Interroge un fournisseur. Si le modèle n'existe plus (les fournisseurs en retirent souvent),
     * Jarvis lit la liste des modèles accessibles avec la clé, choisit le meilleur et réessaie.
     */
    @Throws(AiException::class)
    fun askOne(config: AiProviderConfig, system: String, history: List<ChatMessage>): AiAnswer {
        try {
            return askRaw(config, system, history)
        } catch (e: AiException) {
            if (e.kind != AiErrorKind.BAD_REQUEST) throw e
            val replacement = discoverModel(config)?.takeIf { it != config.model.trim() } ?: throw e
            val answer = askRaw(config.copy(model = replacement), system, history)
            onModelChanged(config.type, replacement)
            return answer
        }
    }

    /** Meilleur modèle disponible pour cette clé, ou null si la liste est inaccessible. */
    fun discoverModel(config: AiProviderConfig): String? = try {
        val (url, headers) = AiProtocol.modelsRequest(config)
        val res = http.get(url, headers, timeoutMs)
        if (res.code in 200..299) AiProtocol.pickModel(config.type, AiProtocol.parseModels(config.type, res.body)) else null
    } catch (_: Exception) { null }

    @Throws(AiException::class)
    private fun askRaw(config: AiProviderConfig, system: String, history: List<ChatMessage>): AiAnswer {
        val req = AiProtocol.buildRequest(config, system, history)
        val res = try {
            http.post(req, timeoutMs)
        } catch (e: java.io.IOException) {
            throw AiException(AiErrorKind.NETWORK, config.type, e.message)
        }
        return AiAnswer(AiProtocol.parseResponse(config.type, res), config.type, config.model)
    }
}
