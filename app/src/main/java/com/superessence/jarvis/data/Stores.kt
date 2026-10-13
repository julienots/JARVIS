package com.superessence.jarvis.data

import android.content.Context
import com.superessence.jarvis.core.brain.ActionLogEntry
import com.superessence.jarvis.core.routine.Routine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Petit stockage JSON local, écrit de façon atomique dans le dossier privé de l'application. */
abstract class JsonFileStore<T>(context: Context, fileName: String) {
    private val file = File(context.filesDir, fileName)
    protected val state: MutableStateFlow<List<T>> by lazy { MutableStateFlow(read()) }
    val flow: StateFlow<List<T>> get() = state.asStateFlow()
    val items: List<T> get() = state.value

    protected abstract fun encode(item: T): JSONObject
    protected abstract fun decode(o: JSONObject): T?

    private fun read(): List<T> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { runCatching { decode(arr.getJSONObject(it)) }.getOrNull() }
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    protected fun write(list: List<T>) {
        state.value = list
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().apply { list.forEach { put(encode(it)) } }.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun clear() = write(emptyList())
}

data class TaskItem(val id: Long, val text: String, val done: Boolean = false, val createdAt: Long = System.currentTimeMillis())

class TaskStore(context: Context) : JsonFileStore<TaskItem>(context, "tasks.json") {
    override fun encode(item: TaskItem) = JSONObject().put("id", item.id).put("text", item.text).put("done", item.done).put("at", item.createdAt)
    override fun decode(o: JSONObject) = TaskItem(o.getLong("id"), o.getString("text"), o.optBoolean("done"), o.optLong("at"))

    fun add(text: String): TaskItem {
        val t = TaskItem(System.nanoTime(), text.replaceFirstChar { it.uppercase() })
        write(items + t); return t
    }
    fun toggle(id: Long) = write(items.map { if (it.id == id) it.copy(done = !it.done) else it })
    fun remove(id: Long) = write(items.filterNot { it.id == id })
    fun pending() = items.filterNot { it.done }
    fun completeMatching(text: String): TaskItem? {
        val n = com.superessence.jarvis.core.text.Text.normalize(text)
        val match = pending().maxByOrNull { com.superessence.jarvis.core.text.Text.similarity(com.superessence.jarvis.core.text.Text.normalize(it.text), n) }
            ?.takeIf { com.superessence.jarvis.core.text.Text.similarity(com.superessence.jarvis.core.text.Text.normalize(it.text), n) >= 0.5 ||
                com.superessence.jarvis.core.text.Text.normalize(it.text).contains(n) }
        match?.let { m -> write(items.map { if (it.id == m.id) it.copy(done = true) else it }) }
        return match
    }
}

data class ReminderItem(val id: Int, val text: String, val atEpochMs: Long)

class ReminderStore(context: Context) : JsonFileStore<ReminderItem>(context, "reminders.json") {
    override fun encode(item: ReminderItem) = JSONObject().put("id", item.id).put("text", item.text).put("at", item.atEpochMs)
    override fun decode(o: JSONObject) = ReminderItem(o.getInt("id"), o.getString("text"), o.getLong("at"))
    fun add(r: ReminderItem) = write(items + r)
    fun remove(id: Int) = write(items.filterNot { it.id == id })
    fun upcoming(now: Long = System.currentTimeMillis()) = items.filter { it.atEpochMs > now }.sortedBy { it.atEpochMs }
}

class RoutineStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_routines", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<List<Routine>> = _flow.asStateFlow()
    val items get() = _flow.value

    private fun load(): List<Routine> {
        if (!prefs.contains("list")) return Routine.examples().also { persist(it) }
        return Routine.listFromJson(prefs.getString("list", null))
    }
    private fun persist(list: List<Routine>) = prefs.edit().putString("list", Routine.listToJson(list)).apply()

    fun upsert(r: Routine) {
        val list = items.toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list += r
        persist(list); _flow.value = list
    }
    fun remove(id: String) { val l = items.filterNot { it.id == id }; persist(l); _flow.value = l }
    fun byId(id: String) = items.firstOrNull { it.id == id }
}

class ActionLogStore(context: Context) : JsonFileStore<ActionLogEntry>(context, "action_log.json") {
    override fun encode(item: ActionLogEntry) = JSONObject().put("t", item.timestampMs).put("id", item.intentId)
        .put("d", item.detail).put("ok", item.success).put("c", item.confirmed)
    override fun decode(o: JSONObject) = ActionLogEntry(o.getLong("t"), o.getString("id"), o.optString("d"), o.optBoolean("ok"), o.optBoolean("c"))
    fun add(e: ActionLogEntry) = write((listOf(e) + items).take(300))
}

data class StoredMessage(val fromUser: Boolean, val text: String, val at: Long)

/** Historique de conversation : désactivé par défaut, stocké uniquement sur le téléphone. */
class ConversationStore(context: Context) : JsonFileStore<StoredMessage>(context, "conversation.json") {
    override fun encode(item: StoredMessage) = JSONObject().put("u", item.fromUser).put("x", item.text).put("t", item.at)
    override fun decode(o: JSONObject) = StoredMessage(o.getBoolean("u"), o.getString("x"), o.getLong("t"))
    fun add(m: StoredMessage) = write((items + m).takeLast(400))
}
