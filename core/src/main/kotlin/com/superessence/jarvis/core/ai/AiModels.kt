package com.superessence.jarvis.core.ai

/** Fournisseurs d'IA pris en charge. Les modèles par défaut sont modifiables dans l'application. */
enum class AiProviderType(
    val displayName: String,
    val defaultModel: String,
    val defaultBaseUrl: String,
    val keyHelpUrl: String,
    val pricingNote: String,
    val requiresKey: Boolean = true,
    /** Modèles retirés par le fournisseur : remplacés automatiquement par [defaultModel]. */
    val retiredModels: Set<String> = emptySet(),
) {
    GEMINI(
        "Google Gemini", "gemini-3.8-flash", "https://generativelanguage.googleapis.com/v1beta",
        "https://aistudio.google.com/apikey", "Offre gratuite avec quotas, puis facturation à l'usage.",
        retiredModels = setOf(
            "gemini-pro", "gemini-1.0-pro", "gemini-1.5-flash", "gemini-1.5-pro",
            "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-2.5-flash",
        ),
    ),
    CLAUDE(
        "Anthropic Claude", "claude-sonnet-5-5", "https://api.anthropic.com/v1",
        "https://console.anthropic.com/settings/keys", "Payant à l'usage (crédits prépayés).",
    ),
    OPENAI(
        "OpenAI ChatGPT", "gpt-4.1-mini", "https://api.openai.com/v1",
        "https://platform.openai.com/api-keys", "Payant à l'usage. L'abonnement ChatGPT Plus n'inclut pas l'API.",
    ),
    GROQ(
        "Groq", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1",
        "https://console.groq.com/keys", "Offre gratuite généreuse avec limites de débit, très rapide.",
    ),
    MISTRAL(
        "Mistral AI", "mistral-small-latest", "https://api.mistral.ai/v1",
        "https://console.mistral.ai/api-keys", "Offre d'essai gratuite limitée, puis payant à l'usage.",
    ),
    CUSTOM(
        "Compatible OpenAI (OpenRouter, Ollama, LM Studio…)", "", "http://192.168.1.10:11434/v1",
        "https://openrouter.ai/keys", "Dépend du service choisi. Un serveur local (Ollama) est gratuit.",
        requiresKey = false,
    );

    /** Renvoie le modèle à utiliser : le modèle par défaut si [model] est vide ou retiré. */
    fun migrateModel(model: String?): String {
        val m = model?.trim().orEmpty()
        return if (m.isEmpty() || m.removePrefix("models/") in retiredModels) defaultModel else m
    }

    val isOpenAiCompatible get() = this == OPENAI || this == GROQ || this == MISTRAL || this == CUSTOM
}

data class AiProviderConfig(
    val type: AiProviderType,
    val apiKey: String = "",
    val model: String = type.defaultModel,
    val baseUrl: String = type.defaultBaseUrl,
    val enabled: Boolean = false,
    /** Gemini uniquement : autorise la recherche Google intégrée pour des réponses à jour. */
    val webGrounding: Boolean = false,
) {
    val isUsable: Boolean
        get() = enabled && model.isNotBlank() && baseUrl.isNotBlank() && (!type.requiresKey || apiKey.isNotBlank())
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(val role: Role, val text: String)

data class HttpRequestSpec(val url: String, val headers: Map<String, String>, val body: String)

data class HttpResult(val code: Int, val body: String)

/** Abstraction HTTP pour pouvoir tester sans réseau. */
fun interface HttpClient {
    @Throws(java.io.IOException::class)
    fun post(request: HttpRequestSpec, timeoutMs: Int): HttpResult
}

enum class AiErrorKind(val userMessage: String) {
    NOT_CONFIGURED("Aucune IA n'est configurée. Ajoutez une clé dans Réglages → Intelligence artificielle."),
    AUTH("La clé API a été refusée. Vérifiez-la dans les réglages."),
    RATE_LIMIT("Le quota ou la limite de requêtes de l'IA est atteint. Réessayez plus tard."),
    NETWORK("Pas de connexion Internet ou le service d'IA ne répond pas."),
    SERVER("Le service d'IA rencontre un problème temporaire."),
    BAD_REQUEST("La requête a été refusée (modèle inconnu ou paramètre invalide). Vérifiez le nom du modèle."),
    BAD_RESPONSE("Réponse de l'IA illisible."),
}

class AiException(val kind: AiErrorKind, val provider: AiProviderType?, detail: String? = null) :
    Exception("${provider?.displayName ?: "IA"} : ${kind.userMessage}${detail?.let { " ($it)" } ?: ""}")

data class AiAnswer(val text: String, val provider: AiProviderType, val model: String)
