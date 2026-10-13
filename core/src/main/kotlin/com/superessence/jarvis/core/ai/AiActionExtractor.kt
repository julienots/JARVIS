package com.superessence.jarvis.core.ai

import com.superessence.jarvis.core.intent.JarvisIntent
import com.superessence.jarvis.core.intent.SettingsTarget
import org.json.JSONObject

/**
 * L'IA peut proposer UNE action par réponse avec la balise : [[ACTION {"type":"open_app","app":"Spotify"}]]
 * L'action proposée repasse par la même politique de sécurité (confirmation, interdictions)
 * que les commandes locales : une réponse de l'IA n'est jamais la preuve qu'une action a eu lieu.
 */
object AiActionExtractor {

    private val tag = Regex("\\[\\[ACTION\\s*(\\{.*?\\})\\s*]]", RegexOption.DOT_MATCHES_ALL)

    data class Extracted(val speech: String, val intent: JarvisIntent?)

    val protocolDescription = """
        Tu peux déclencher au maximum une action sur le téléphone en ajoutant à la fin de ta réponse une balise :
        [[ACTION {"type":"...", ...}]]
        Types disponibles :
        - {"type":"open_app","app":"nom de l'application"}
        - {"type":"open_settings","target":"WIFI|BLUETOOTH|DISPLAY|SOUND|LOCATION|BATTERY|AIRPLANE|NFC|NOTIFICATIONS|APPS|STORAGE|GENERAL"}
        - {"type":"timer","seconds":300}
        - {"type":"alarm","hour":7,"minute":30}
        - {"type":"reminder","text":"...","minutes":10}
        - {"type":"add_task","text":"..."}
        - {"type":"web_search","query":"..."}
        - {"type":"navigate","destination":"..."}
        - {"type":"play_music","query":"..."}
        - {"type":"weather","city":"..."}
        - {"type":"call","contact":"..."}   (sera confirmé par l'utilisateur)
        - {"type":"send_message","contact":"...","message":"..."}   (sera confirmé, l'utilisateur appuie lui-même sur Envoyer)
        N'affirme jamais qu'une action est faite : dis plutôt que tu la lances. N'invente pas d'actions hors de cette liste.
    """.trimIndent()

    fun extract(answer: String): Extracted {
        val m = tag.find(answer) ?: return Extracted(answer.trim(), null)
        val speech = answer.replace(tag, "").trim()
        val intent = try { toIntent(JSONObject(m.groupValues[1])) } catch (_: Exception) { null }
        return Extracted(speech, intent)
    }

    fun toIntent(j: JSONObject): JarvisIntent? = when (j.optString("type")) {
        "open_app" -> j.optString("app").takeIf { it.isNotBlank() }?.let { JarvisIntent.OpenApp(it) }
        "open_settings" -> runCatching { SettingsTarget.valueOf(j.optString("target").uppercase()) }.getOrNull()?.let { JarvisIntent.OpenSettings(it) }
        "timer" -> j.optInt("seconds", 0).takeIf { it in 1..86_400 }?.let { JarvisIntent.Timer(it) }
        "alarm" -> {
            val h = j.optInt("hour", -1); val m = j.optInt("minute", 0)
            if (h in 0..23 && m in 0..59) JarvisIntent.Alarm(h, m) else null
        }
        "reminder" -> j.optString("text").takeIf { it.isNotBlank() }?.let {
            JarvisIntent.Reminder(it, j.optInt("minutes", 0).takeIf { m -> m > 0 }, null, null)
        }
        "add_task" -> j.optString("text").takeIf { it.isNotBlank() }?.let { JarvisIntent.AddTask(it) }
        "web_search" -> j.optString("query").takeIf { it.isNotBlank() }?.let { JarvisIntent.WebSearch(it) }
        "navigate" -> j.optString("destination").takeIf { it.isNotBlank() }?.let { JarvisIntent.Navigate(it) }
        "play_music" -> j.optString("query").takeIf { it.isNotBlank() }?.let { JarvisIntent.PlayMusic(it) }
        "weather" -> JarvisIntent.Weather(j.optString("city").ifBlank { null })
        "call" -> j.optString("contact").takeIf { it.isNotBlank() }?.let { JarvisIntent.Call(it) }
        "send_message" -> j.optString("contact").takeIf { it.isNotBlank() }?.let { JarvisIntent.SendMessage(it, j.optString("message")) }
        else -> null
    }
}
