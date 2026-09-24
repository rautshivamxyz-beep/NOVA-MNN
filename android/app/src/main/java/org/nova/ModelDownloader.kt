package org.nova

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model downloader with resume support.
 *
 * Two shapes of download:
 *  - single files (legacy .gguf path, kept for custom URLs)
 *  - Hugging Face REPO urls -> a multi-file MNN model directory
 *    (config.json + weights + tokenizer), fetched via the HF tree API.
 *
 * Files land as "<dest>.part" and are atomically renamed on completion,
 * so a killed download never leaves a model that looks complete.
 * Interrupted downloads resume with HTTP Range requests.
 */
object ModelDownloader {

    sealed class State {
        object Idle : State()
        data class Downloading(
            val name: String,
            val downloaded: Long,
            val total: Long // -1 if unknown
        ) : State()

        data class Done(val file: File) : State()
        data class Failed(val name: String, val error: String) : State()
        data class Importing(val name: String, val copied: Long, val total: Long) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cancelled = false

    val isBusy: Boolean get() = _state.value is State.Downloading || _state.value is State.Importing

    /** Dismisses a terminal (Done/Failed) state back to Idle. */
    fun acknowledge() {
        val s = _state.value
        if (s is State.Done || s is State.Failed) _state.value = State.Idle
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
    }

    /** True when the URL is a Hugging Face model REPO (no file in it).
     *  Repo names may contain dots (Qwen2.5-1.5B-Instruct-MNN), so we
     *  look at the path shape instead: owner/repo with no /resolve/,
     *  /blob/ or extra segments means a repo. */
    fun isRepoUrl(url: String): Boolean {
        if (!url.startsWith("https://huggingface.co/")) return false
        val path = url.removePrefix("https://huggingface.co/")
            .substringBefore('?').trim('/')
        if (path.isEmpty()) return false
        val segs = path.split('/')
        if (segs.size != 2) return false // direct file links have more segments
        return !segs[1].lowercase().endsWith(".gguf")
    }

    /**
     * Starts a download of either shape. Only one transfer runs at a
     * time. Observe [state] for progress.
     */
    fun download(url: String, dir: File) {
        if (isBusy) return
        cancelled = false
        job = scope.launch {
            try {
                _state.value = if (isRepoUrl(url)) doDownloadRepo(url, dir) else doDownload(url, dir)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = State.Idle
            } catch (e: Exception) {
                _state.value = State.Failed(fileNameFromUrl(url), e.message ?: "download failed")
            }
        }
    }

    /**
     * Copies a user-picked model file (SAF Uri already opened as stream)
     * into the models directory.
     */
    fun import(name: String, input: () -> java.io.InputStream?, totalHint: Long, dir: File) {
        if (isBusy) return
        cancelled = false
        job = scope.launch {
            try {
                val dest = uniqueFile(File(dir, sanitize(name)))
                val part = File(dest.absolutePath + ".part")
                var copied = 0L
                val buf = ByteArray(64 * 1024)
                input()?.use { ins ->
                    FileOutputStream(part).use { out ->
                        while (true) {
                            if (cancelled) throw IOException("cancelled")
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            _state.value = State.Importing(dest.name, copied, totalHint)
                        }
                    }
                } ?: throw IOException("cannot open selected file")
                if (copied < 16) throw IOException("selected file is not a valid model")
                if (!part.renameTo(dest)) throw IOException("rename failed")
                _state.value = State.Done(dest)
            } catch (e: kotlinx.coroutines.CancellationException) {
                File(dir, sanitize(name) + ".part").delete()
                _state.value = State.Idle
            } catch (e: Exception) {
                _state.value = State.Failed(name, e.message ?: "import failed")
            }
        }
    }

    // ------------------------------------------------ multi-file MNN repos

    private suspend fun doDownloadRepo(repoUrl: String, dir: File): State = withContext(Dispatchers.IO) {
        val repo = repoUrl.removePrefix("https://huggingface.co/").substringBefore('?').trim('/')
        if (!repo.contains('/')) throw IOException("not a Hugging Face repo url")
        val label = repo.substringAfterLast('/')
        val modelDir = File(dir, sanitize(label))
        modelDir.mkdirs()

        val tree = fetchJson("https://huggingface.co/api/models/$repo/tree/main")
        val arr = JSONArray(tree)
        val files = ArrayList<Pair<String, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.getString("path")
            if (p == ".gitattributes" || p.endsWith(".md")) continue
            files.add(p to o.optLong("size", -1L))
        }
        if (files.isEmpty()) throw IOException("no files found in repo")
        val total = files.map { it.second }.sum()

        var base = 0L
        for ((p, sz) in files) {
            val dest = File(modelDir, sanitize(p))
            val done = downloadOne(
                "https://huggingface.co/$repo/resolve/main/$p",
                dest, base, total, label
            )
            base += if (sz > 0) sz else done
        }
        State.Done(modelDir)
    }

    /** One resumable file download; returns the bytes on disk afterwards. */
    private fun downloadOne(url: String, dest: File, base: Long, total: Long, label: String): Long {
        if (dest.exists()) return dest.length()
        val part = File(dest.absolutePath + ".part")
        var conn: HttpURLConnection? = null
        try {
            val existing = if (part.exists()) part.length() else 0L
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "NOVA-Android/2.0")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IOException("HTTP $code for ${dest.name}")
            val resume = code == 206 && existing > 0
            var done = if (resume) existing else 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { ins ->
                FileOutputStream(part, resume).use { out ->
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = State.Downloading(label, base + done, if (total > 0) total else -1L)
                    }
                    out.fd.sync()
                }
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return done
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchJson(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "NOVA-Android/2.0")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} listing repo")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------ single .gguf files

    private suspend fun doDownload(url: String, dir: File): State = withContext(Dispatchers.IO) {
        val fileName = sanitize(fileNameFromUrl(url))
        if (!fileName.endsWith(".gguf")) throw IOException("URL does not point to a .gguf file")
        val dest = uniqueFile(File(dir, fileName))
        val part = File(dir, dest.name + ".part")

        var conn: HttpURLConnection? = null
        try {
            val existing = if (part.exists()) part.length() else 0L
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20000
                readTimeout = 30000
                setRequestProperty("User-Agent", "NOVA-Android/2.0")
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }

            val code = conn.responseCode
            if (code != 200 && code != 206) {
                throw IOException("HTTP $code from server")
            }

            // 200 = server ignored the range request -> restart from zero
            val resume = code == 206 && existing > 0
            val newBytes = conn.contentLengthLong
            val total = if (newBytes > 0) {
                (if (resume) existing + newBytes else newBytes)
            } else -1L

            var done = if (resume) existing else 0L
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { ins ->
                FileOutputStream(part, resume).use { out ->
                    while (true) {
                        if (cancelled) throw IOException("cancelled")
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = State.Downloading(dest.name, done, total)
                    }
                    out.fd.sync()
                }
            }

            if (total > 0 && done < total) throw IOException("incomplete download")
            if (!part.renameTo(dest)) {
                // rename can fail across weird mount points; fall back to copy
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            State.Done(dest)
        } finally {
            conn?.disconnect()
        }
    }

    private fun uniqueFile(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension
        val ext = target.extension
        var i = 1
        while (true) {
            val f = File(target.parentFile, "$base-$i.$ext")
            if (!f.exists()) return f
            i++
        }
    }

    fun fileNameFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?').ifBlank { "model.gguf" }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
}
