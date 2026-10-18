package com.superessence.jarvis.data

import android.content.Context
import com.superessence.jarvis.core.ai.AiProviderConfig
import com.superessence.jarvis.core.ai.AiProviderType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WakeEngineChoice(val label: String, val description: String) {
    AUTO("Automatique", "Moteur hors ligne Vosk s'il est installé, sinon reconnaissance Android."),
    VOSK("Hors ligne (Vosk)", "Détection 100 % locale, aucune donnée envoyée. Nécessite le modèle français (~41 Mo)."),
    ANDROID("Reconnaissance Android", "Utilise le service vocal du téléphone. Plus gourmand en batterie, peut émettre un bip."),
}

enum class SttEngineChoice(val label: String, val description: String) {
    ANDROID("Android (Google…)", "Meilleure précision. Peut passer par les serveurs du fournisseur du téléphone."),
    VOSK("Hors ligne (Vosk)", "Fonctionne sans Internet, précision plus modeste."),
}

enum class SphereStyle(val label: String) { HOLOGRAM("Hologramme cyan"), GOLD("Or SuperEssence"), NEBULA("Nébuleuse violette"), EMERALD("Émeraude") }

data class JarvisSettings(
    val onboardingDone: Boolean = false,
    val wakeConsent: Boolean = false,
    val wakeEnabled: Boolean = false,
    val wakeWord: String = "Jarvis",
    val wakeEngine: WakeEngineChoice = WakeEngineChoice.AUTO,
    val sttEngine: SttEngineChoice = SttEngineChoice.ANDROID,
    val continuousConversation: Boolean = true,
    val maxFollowUps: Int = 4,
    val overlayEnabled: Boolean = true,
    val overlayAutoHideSeconds: Int = 7,
    /** La sphère reste par-dessus les autres applications, prête à être touchée. */
    val overlayAlwaysOn: Boolean = false,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 0.95f,
    val ttsVoice: String = "",
    /** Voix Jarvis : voix masculine grave + effets, locale et illimitée. */
    val jarvisVoice: Boolean = true,
    val earcons: Boolean = true,
    val ecoMode: Boolean = false,
    val sphereStyle: SphereStyle = SphereStyle.HOLOGRAM,
    val privateMode: Boolean = false,
    val historyEnabled: Boolean = false,
    val actionLogEnabled: Boolean = true,
    val allowAiActions: Boolean = true,
    val userName: String = "",
    val defaultCity: String = "",
    val resumeAfterBoot: Boolean = true,
    val providerOrder: List<AiProviderType> = AiProviderType.entries.toList(),
)

/** Réglages non secrets (SharedPreferences) + configuration des fournisseurs d'IA. */
class SettingsRepository(context: Context, private val secrets: SecureKeyStore) {

    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<JarvisSettings> = _flow.asStateFlow()
    val current get() = _flow.value

    private val _providers = MutableStateFlow(loadProviders())
    val providers: StateFlow<List<AiProviderConfig>> = _providers.asStateFlow()

    fun update(transform: (JarvisSettings) -> JarvisSettings) {
        val next = transform(_flow.value)
        save(next)
        _flow.value = next
    }

    /** Fournisseurs dans l'ordre de priorité choisi. */
    fun orderedProviders(): List<AiProviderConfig> {
        val byType = _providers.value.associateBy { it.type }
        return current.providerOrder.mapNotNull { byType[it] }
    }

    fun saveProvider(config: AiProviderConfig) {
        val t = config.type.name
        prefs.edit()
            .putBoolean("ai_${t}_enabled", config.enabled)
            .putString("ai_${t}_model", config.model)
            .putString("ai_${t}_url", config.baseUrl)
            .putBoolean("ai_${t}_web", config.webGrounding)
            .apply()
        secrets.put("key_$t", config.apiKey)
        _providers.value = loadProviders()
    }

    fun moveProvider(type: AiProviderType, delta: Int) = update { s ->
        val list = s.providerOrder.toMutableList()
        val i = list.indexOf(type)
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i >= 0 && i != j) { list.removeAt(i); list.add(j, type) }
        s.copy(providerOrder = list)
    }

    fun forgetAllKeys() {
        secrets.clearAll()
        _providers.value = loadProviders()
    }

    private fun loadProviders(): List<AiProviderConfig> = AiProviderType.entries.map { t ->
        AiProviderConfig(
            type = t,
            apiKey = secrets.get("key_${t.name}"),
            model = prefs.getString("ai_${t.name}_model", null) ?: t.defaultModel,
            baseUrl = prefs.getString("ai_${t.name}_url", null) ?: t.defaultBaseUrl,
            enabled = prefs.getBoolean("ai_${t.name}_enabled", false),
            webGrounding = prefs.getBoolean("ai_${t.name}_web", false),
        )
    }

    private fun load(): JarvisSettings {
        val d = JarvisSettings()
        val order = prefs.getString("provider_order", null)?.split(',')
            ?.mapNotNull { runCatching { AiProviderType.valueOf(it) }.getOrNull() }
            ?.let { saved -> saved + AiProviderType.entries.filter { it !in saved } } ?: d.providerOrder
        return JarvisSettings(
            onboardingDone = prefs.getBoolean("onboardingDone", d.onboardingDone),
            wakeConsent = prefs.getBoolean("wakeConsent", d.wakeConsent),
            wakeEnabled = prefs.getBoolean("wakeEnabled", d.wakeEnabled),
            wakeWord = prefs.getString("wakeWord", d.wakeWord) ?: d.wakeWord,
            wakeEngine = enumOr(prefs.getString("wakeEngine", null), d.wakeEngine),
            sttEngine = enumOr(prefs.getString("sttEngine", null), d.sttEngine),
            continuousConversation = prefs.getBoolean("continuousConversation", d.continuousConversation),
            maxFollowUps = prefs.getInt("maxFollowUps", d.maxFollowUps),
            overlayEnabled = prefs.getBoolean("overlayEnabled", d.overlayEnabled),
            overlayAutoHideSeconds = prefs.getInt("overlayAutoHideSeconds", d.overlayAutoHideSeconds),
            overlayAlwaysOn = prefs.getBoolean("overlayAlwaysOn", d.overlayAlwaysOn),
            ttsRate = prefs.getFloat("ttsRate", d.ttsRate),
            ttsPitch = prefs.getFloat("ttsPitch", d.ttsPitch),
            ttsVoice = prefs.getString("ttsVoice", d.ttsVoice) ?: "",
            jarvisVoice = prefs.getBoolean("jarvisVoice", d.jarvisVoice),
            earcons = prefs.getBoolean("earcons", d.earcons),
            ecoMode = prefs.getBoolean("ecoMode", d.ecoMode),
            sphereStyle = enumOr(prefs.getString("sphereStyle", null), d.sphereStyle),
            privateMode = prefs.getBoolean("privateMode", d.privateMode),
            historyEnabled = prefs.getBoolean("historyEnabled", d.historyEnabled),
            actionLogEnabled = prefs.getBoolean("actionLogEnabled", d.actionLogEnabled),
            allowAiActions = prefs.getBoolean("allowAiActions", d.allowAiActions),
            userName = prefs.getString("userName", d.userName) ?: "",
            defaultCity = prefs.getString("defaultCity", d.defaultCity) ?: "",
            resumeAfterBoot = prefs.getBoolean("resumeAfterBoot", d.resumeAfterBoot),
            providerOrder = order,
        )
    }

    private fun save(s: JarvisSettings) {
        prefs.edit()
            .putBoolean("onboardingDone", s.onboardingDone)
            .putBoolean("wakeConsent", s.wakeConsent)
            .putBoolean("wakeEnabled", s.wakeEnabled)
            .putString("wakeWord", s.wakeWord)
            .putString("wakeEngine", s.wakeEngine.name)
            .putString("sttEngine", s.sttEngine.name)
            .putBoolean("continuousConversation", s.continuousConversation)
            .putInt("maxFollowUps", s.maxFollowUps)
            .putBoolean("overlayEnabled", s.overlayEnabled)
            .putInt("overlayAutoHideSeconds", s.overlayAutoHideSeconds)
            .putBoolean("overlayAlwaysOn", s.overlayAlwaysOn)
            .putFloat("ttsRate", s.ttsRate)
            .putFloat("ttsPitch", s.ttsPitch)
            .putString("ttsVoice", s.ttsVoice)
            .putBoolean("jarvisVoice", s.jarvisVoice)
            .putBoolean("earcons", s.earcons)
            .putBoolean("ecoMode", s.ecoMode)
            .putString("sphereStyle", s.sphereStyle.name)
            .putBoolean("privateMode", s.privateMode)
            .putBoolean("historyEnabled", s.historyEnabled)
            .putBoolean("actionLogEnabled", s.actionLogEnabled)
            .putBoolean("allowAiActions", s.allowAiActions)
            .putString("userName", s.userName)
            .putString("defaultCity", s.defaultCity)
            .putBoolean("resumeAfterBoot", s.resumeAfterBoot)
            .putString("provider_order", s.providerOrder.joinToString(",") { it.name })
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, def: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def
}
