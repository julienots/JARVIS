package com.superessence.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Télécharge et charge le petit modèle français Vosk (licence Apache 2.0, ~41 Mo)
 * qui permet la détection du mot « Jarvis » et la transcription entièrement hors ligne.
 */
class VoskModelManager(private val context: Context) {

    sealed class Status {
        data object Missing : Status()
        data class Downloading(val progress: Float) : Status()
        data object Unpacking : Status()
        data object Ready : Status()
        data class Failed(val message: String) : Status()
    }

    companion object {
        const val MODEL_NAME = "vosk-model-small-fr-0.22"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        const val MODEL_SIZE_MB = 41
    }

    private val root = File(context.filesDir, "vosk")
    private val modelDir = File(root, MODEL_NAME)
    private val _status = MutableStateFlow<Status>(if (isInstalled()) Status.Ready else Status.Missing)
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var model: Model? = null

    fun isInstalled() = File(modelDir, "am").exists() || File(modelDir, "conf").exists()

    /** Charge le modèle en mémoire (opération lente, à faire hors du thread principal). */
    @Synchronized
    fun loadModel(): Model? {
        model?.let { return it }
        if (!isInstalled()) return null
        return try { Model(modelDir.absolutePath).also { model = it } } catch (e: Throwable) {
            _status.value = Status.Failed("Modèle vocal illisible : ${e.message}"); null
        }
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        try {
            root.mkdirs()
            val zip = File(root, "$MODEL_NAME.zip.part")
            _status.value = Status.Downloading(0f)
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (MODEL_SIZE_MB * 1_000_000L)
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _status.value = Status.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            _status.value = Status.Unpacking
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                val canonicalRoot = root.canonicalPath + File.separator
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = File(root, e.name)
                    // Protection contre les chemins malveillants dans l'archive (« zip slip »)
                    if (!out.canonicalPath.startsWith(canonicalRoot)) continue
                    if (e.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            zip.delete()
            if (!isInstalled()) throw IllegalStateException("archive inattendue")
            _status.value = Status.Ready
            true
        } catch (e: Exception) {
            _status.value = Status.Failed("Téléchargement impossible : ${e.message ?: "erreur réseau"}")
            false
        }
    }

    fun delete() {
        model?.close(); model = null
        modelDir.deleteRecursively()
        _status.value = Status.Missing
    }
}
