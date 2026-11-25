/*
 * Derived from the JARVIS project (com.jarvis.assistant.audio.VoskModelManager).
 * Original attribution and license information: see THIRD_PARTY_NOTICES.md.
 * Adapted for NEXA AI: checksum-verified atomic install (bundled assets or optional download), progress
 * StateFlow, models loaded on demand and released afterwards, app keeps working when the models are
 * missing. Modifications © ARPON.
 */
package com.example.voice.owner

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.SpeakerModel
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

sealed interface VoskModelState {
    /** Neither installed nor installable from this build. */
    data object Unavailable : VoskModelState
    data object NotInstalled : VoskModelState
    data class Installing(val progress: Float, val step: String) : VoskModelState
    /** Files verified on disk; native models are not loaded into memory. */
    data object Installed : VoskModelState
    data object Loaded : VoskModelState
    data class Failed(val reason: String) : VoskModelState
}

/** Optional first-run download. Both fields must be set (and the hash verified by the maintainer) before it is used. */
data class VoskDownload(val url: String, val sha256: String, val approxBytes: Long)

object VoskDownloadConfig {
    /** Not configured: the official archive URLs and their SHA-256 must be verified by the maintainer first. */
    val EN_US: VoskDownload? = null
    val SPEAKER: VoskDownload? = null
}

internal object ZipSafety {
    /** Resolves [entryName] under [destDir]; throws on path traversal ("zip-slip"). */
    fun resolve(destDir: File, entryName: String): File {
        val out = File(destDir, entryName)
        val destCanonical = destDir.canonicalPath + File.separator
        if (!out.canonicalPath.startsWith(destCanonical)) throw IOException("Blocked unsafe zip entry")
        return out
    }
}

internal fun sha256Hex(digest: MessageDigest): String =
    digest.digest().joinToString("") { "%02x".format(it) }

class VoskModelManager(context: Context) {

    companion object {
        private const val TAG = "VoskModelManager"
        private const val ROOT = "vosk"
        private const val ASSET_ROOT = "vosk"
        private const val MANIFEST = "$ASSET_ROOT/manifest.json"
        private const val OK_MARKER = ".installed_ok"
        const val PKG_EN_US = "model-en-us"
        const val PKG_SPEAKER = "model-spk"
        private val LAYOUT_CHECK = mapOf(PKG_EN_US to "am/final.mdl", PKG_SPEAKER to "final.ext.raw")
    }

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, ROOT)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<VoskModelState>(VoskModelState.NotInstalled)
    val state: StateFlow<VoskModelState> = _state.asStateFlow()

    @Volatile private var model: Model? = null
    @Volatile private var speakerModel: SpeakerModel? = null

    /** Both loaded native models, or null. Valid until [unload]. */
    fun loadedModels(): Pair<Model, SpeakerModel>? {
        val m = model; val s = speakerModel
        return if (m != null && s != null) m to s else null
    }

    fun hasBundledAssets(): Boolean = readManifest() != null
    fun downloadConfigured(): Boolean = VoskDownloadConfig.EN_US != null && VoskDownloadConfig.SPEAKER != null

    private fun dir(id: String) = File(root, id)
    private fun isInstalled(id: String): Boolean {
        val layout = LAYOUT_CHECK[id] ?: return false
        return File(dir(id), OK_MARKER).isFile && File(dir(id), layout).let { it.isFile && it.length() > 0 }
    }

    /** Cheap, no native code: updates [state] from what is on disk / in the APK. */
    fun refreshState() {
        if (_state.value is VoskModelState.Installing || _state.value == VoskModelState.Loaded) return
        _state.value = when {
            isInstalled(PKG_EN_US) && isInstalled(PKG_SPEAKER) -> VoskModelState.Installed
            hasBundledAssets() || downloadConfigured() -> VoskModelState.NotInstalled
            else -> VoskModelState.Unavailable
        }
    }

    // ---- install --------------------------------------------------------------------------

    /** Installs from bundled assets when present, otherwise from the configured download. */
    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInstalled(PKG_EN_US) && isInstalled(PKG_SPEAKER)) { _state.value = VoskModelState.Installed; return@withLock true }
            try {
                root.mkdirs()
                val manifest = readManifest()
                if (manifest != null) installFromAssets(manifest)
                else if (downloadConfigured()) installFromDownload()
                else { _state.value = VoskModelState.Unavailable; return@withLock false }
                _state.value = VoskModelState.Installed
                true
            } catch (e: Exception) {
                Log.w(TAG, "Install failed: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = VoskModelState.Failed(e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    private data class AssetFile(val path: String, val size: Long, val sha256: String)
    private data class AssetManifest(val packages: Map<String, List<AssetFile>>) {
        val totalBytes: Long get() = packages.values.sumOf { l -> l.sumOf { it.size } }
    }

    private fun readManifest(): AssetManifest? = try {
        val text = appContext.assets.open(MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
        val pk = JSONObject(text).getJSONObject("packages")
        val map = LinkedHashMap<String, List<AssetFile>>()
        for (id in pk.keys()) {
            val arr = pk.getJSONArray(id)
            map[id] = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                AssetFile(o.getString("path"), o.getLong("size"), o.getString("sha256"))
            }
        }
        if (map.keys.containsAll(listOf(PKG_EN_US, PKG_SPEAKER))) AssetManifest(map) else null
    } catch (_: Exception) { null }

    private fun installFromAssets(manifest: AssetManifest) {
        if (appContext.filesDir.usableSpace < manifest.totalBytes * 2) throw IOException("Not enough free storage")
        var done = 0L
        for ((id, files) in manifest.packages) {
            if (isInstalled(id)) { done += files.sumOf { it.size }; continue }
            val tmp = File(root, "$id.tmp"); tmp.deleteRecursively(); tmp.mkdirs()
            for (f in files) {
                val target = ZipSafety.resolve(tmp, f.path)
                target.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                appContext.assets.open("$ASSET_ROOT/$id/${f.path}").use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n)
                            done += n
                            _state.value = VoskModelState.Installing(done.toFloat() / manifest.totalBytes, id)
                        }
                    }
                }
                if (sha256Hex(digest) != f.sha256.lowercase() || target.length() != f.size) {
                    tmp.deleteRecursively(); throw IOException("Checksum mismatch in $id/${f.path}")
                }
            }
            commit(id, tmp)
        }
    }

    private fun installFromDownload() {
        val jobs = listOf(PKG_EN_US to VoskDownloadConfig.EN_US!!, PKG_SPEAKER to VoskDownloadConfig.SPEAKER!!)
        val total = jobs.sumOf { it.second.approxBytes }.coerceAtLeast(1)
        if (appContext.filesDir.usableSpace < total * 3) throw IOException("Not enough free storage")
        val http = OkHttpClient()
        var done = 0L
        for ((id, dl) in jobs) {
            if (isInstalled(id)) { done += dl.approxBytes; continue }
            require(dl.sha256.length == 64) { "No SHA-256 configured for $id" }
            val zip = File(root, "$id.zip.part")
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(Request.Builder().url(dl.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("Empty response")
                body.byteStream().use { input -> zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        _state.value = VoskModelState.Installing((done.toFloat() / total).coerceAtMost(0.95f), id)
                    }
                } }
            }
            if (sha256Hex(digest) != dl.sha256.lowercase()) { zip.delete(); throw IOException("Checksum mismatch for $id") }
            val tmp = File(root, "$id.tmp"); tmp.deleteRecursively(); tmp.mkdirs()
            zip.inputStream().use { unzip(it, tmp) }
            zip.delete()
            // Archives usually contain one top-level folder: lift its contents.
            val children = tmp.listFiles().orEmpty()
            val src = if (children.size == 1 && children[0].isDirectory) children[0] else tmp
            val staged = File(root, "$id.stage"); staged.deleteRecursively()
            if (!src.renameTo(staged)) throw IOException("Could not stage $id")
            tmp.deleteRecursively()
            commit(id, staged)
        }
    }

    private fun unzip(input: InputStream, dest: File) {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = ZipSafety.resolve(dest, entry.name)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun commit(id: String, staged: File) {
        val layout = LAYOUT_CHECK.getValue(id)
        if (!File(staged, layout).isFile) { staged.deleteRecursively(); throw IOException("Unexpected layout in $id") }
        File(staged, OK_MARKER).writeText("ok")
        val target = dir(id)
        target.deleteRecursively()
        if (!staged.renameTo(target)) { staged.deleteRecursively(); throw IOException("Could not finalise $id") }
    }

    // ---- load / unload / delete ----------------------------------------------------------

    /** Loads the native models. Returns false (never throws) if missing or native loading fails. */
    suspend fun load(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loadedModels() != null) return@withLock true
            if (!(isInstalled(PKG_EN_US) && isInstalled(PKG_SPEAKER))) return@withLock false
            try {
                val m = Model(dir(PKG_EN_US).absolutePath)
                val s = try { SpeakerModel(dir(PKG_SPEAKER).absolutePath) } catch (t: Throwable) { m.close(); throw t }
                model = m; speakerModel = s
                _state.value = VoskModelState.Loaded
                true
            } catch (t: Throwable) { // includes UnsatisfiedLinkError when the native lib is absent for this ABI
                Log.w(TAG, "Model load failed: ${t.javaClass.simpleName}")
                _state.value = VoskModelState.Failed("Model could not be loaded (${t.javaClass.simpleName})")
                false
            }
        }
    }

    /** Frees native memory. Safe to call repeatedly. */
    fun unload() {
        val m = model; val s = speakerModel
        model = null; speakerModel = null
        runCatching { s?.close() }; runCatching { m?.close() }
        if (_state.value == VoskModelState.Loaded) _state.value = VoskModelState.Installed
    }

    suspend fun deleteInstalled() = withContext(Dispatchers.IO) {
        mutex.withLock {
            unload()
            root.deleteRecursively()
            _state.value = if (hasBundledAssets() || downloadConfigured()) VoskModelState.NotInstalled else VoskModelState.Unavailable
        }
    }
}
