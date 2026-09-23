package org.nova

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.nova.mnn.MnnEngine
import java.io.File

/**
 * App-scoped holder for the MNN inference engine (NOVA-MNN build).
 * Same public surface as the llama.cpp version, so the rest of the
 * app is unchanged. Loading runs in [scope], which lives as long as
 * the process - leaving the Models screen never cancels a load.
 */
object NovaEngine {

    /** Lifecycle of a model load, for the UI to observe. */
    sealed class LoadState {
        object Idle : LoadState()
        data class Loading(val label: String) : LoadState()
        object Ready : LoadState()
        data class Failed(val label: String, val error: String?) : LoadState()
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Idle)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    /** Dismiss a terminal (Ready/Failed) state back to Idle. */
    fun acknowledgeLoad() {
        val s = _loadState.value
        if (s is LoadState.Ready || s is LoadState.Failed) _loadState.value = LoadState.Idle
    }

    /** App-lifetime scope: model loads run here so they survive navigation. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var engine: MnnEngine? = null

    /** System prompt to prepend to the first generation after a load. */
    @Volatile
    private var pendingSystem: String = ""

    /** True while a generation is streaming - load() waits for it. */
    @Volatile
    private var busy: Boolean = false

    @Volatile
    var activeModelPath: String? = null
        private set

    @Volatile
    var activeModelLabel: String = ""
        private set

    /** True after any send() - the conversation context is no longer clean. */
    @Volatile
    var contextDirty: Boolean = false
        private set

    @Volatile
    private var loading = false

    /** True while a model load/reload is in progress. */
    val isLoading: Boolean get() = loading

    /**
     * Big-core count (v5.3 logic, Kotlin side): cores at >= 80% of the
     * fastest core's max frequency. MNN threads follow the big cores.
     */
    private fun bigCores(): Int {
        return try {
            val cpus = File("/sys/devices/system/cpu/").listFiles { f: File ->
                f.name.startsWith("cpu") && f.name.removePrefix("cpu").toIntOrNull() != null
            } ?: return 4
            val maxes = ArrayList<Int>()
            for (c in cpus) {
                val f = File(c, "cpufreq/cpuinfo_max_freq")
                if (f.exists()) f.readText().trim().toIntOrNull()?.let { maxes.add(it) }
            }
            if (maxes.isEmpty()) return 4
            val top = maxes.max()
            val big = maxes.count { it >= top * 8 / 10 }
            if (big in 1..8) big else 4
        } catch (e: Exception) {
            4
        }
    }

    /**
     * Starts loading a model in the app scope (survives navigation).
     * [path] may be a model directory (containing config.json) or the
     * config.json file itself. Observe [loadState] for the result.
     */
    fun loadAsync(context: Context, path: String, label: String, systemPrompt: String) {
        if (loading) return
        loading = true
        _loadState.value = LoadState.Loading(label)
        val appContext = context.applicationContext
        scope.launch {
            try {
                load(appContext, path, label, systemPrompt)
                Settings(appContext).let {
                    it.lastModelPath = path
                    it.lastModelLabel = label
                }
                _loadState.value = LoadState.Ready
            } catch (e: CancellationException) {
                _loadState.value = LoadState.Failed(label, "cancelled")
            } catch (e: Exception) {
                _loadState.value = LoadState.Failed(label, e.message ?: "unknown error")
            } finally {
                loading = false
            }
        }
    }

    suspend fun load(context: Context, path: String, label: String, systemPrompt: String) {
        // let a running generation finish before releasing the engine
        var waited = 0
        while (busy && waited < 5000) {
            delay(200)
            waited += 200
        }
        val configPath = if (path.endsWith(".json")) path
        else File(path, "config.json").let { if (it.exists()) it.absolutePath else path }
        unloadInternal()
        val eng = MnnEngine()
        val ok = withContext(Dispatchers.IO) { eng.init(configPath, bigCores()) }
        if (!ok) {
            try { eng.release() } catch (e: Exception) { }
            throw IllegalStateException("MNN could not load $configPath")
        }
        engine = eng
        pendingSystem = (systemPrompt + thinkingHint(path, label)).trim()
        activeModelPath = path
        activeModelLabel = label
        contextDirty = false
    }

    /**
     * Reasoning models burn response time on hidden thinking chains -
     * tell them to keep it short so answers arrive fast.
     */
    private fun thinkingHint(path: String, label: String): String {
        val n = (label + " " + path.substringAfterLast('/')).lowercase()
        if ("think" !in n) return ""
        return "\n\n(You are a reasoning model. Keep your hidden thinking SHORT: " +
            "one or two brief lines for easy questions, detailed step-by-step " +
            "reasoning only for genuinely hard problems. Then answer directly.)"
    }

    /** Reloads the active model, starting a fresh conversation. */
    fun reloadAsync(context: Context, systemPrompt: String) {
        val path = activeModelPath ?: return
        val label = activeModelLabel
        loadAsync(context, path, label, systemPrompt)
    }

    suspend fun unload(context: Context) {
        activeModelPath = null
        activeModelLabel = ""
        unloadInternal()
    }

    private fun unloadInternal() {
        val old = engine
        engine = null
        try {
            old?.stop()
            old?.release()
        } catch (e: Exception) {
            // best effort
        }
    }

    /**
     * Streams the model's reply as tokens. Cancelling the collector
     * (STOP button) stops generation cleanly after the current token.
     */
    fun send(message: String, predictLength: Int): Flow<String> = callbackFlow {
        val eng = requireNotNull(engine) { "No model loaded" }
        contextDirty = true
        val sys = pendingSystem
        if (sys.isNotEmpty()) pendingSystem = ""
        eng.listener = MnnEngine.TokenListener { t ->
            trySend(t)
        }
        busy = true
        launch(Dispatchers.IO) {
            try {
                val full = if (sys.isNotEmpty()) sys + "\n\n" + message else message
                eng.submit(full, if (predictLength > 0) predictLength else 2048)
            } catch (e: Exception) {
                close(e)
            } finally {
                eng.listener = null
                busy = false
                close()
            }
        }
        awaitClose {
            eng.stop()
        }
    }

    val isModelLoaded: Boolean
        get() = engine?.isLoaded == true

    /** Decode tokens per second of the last generation, 0 if unknown. */
    val decodeSpeed: Float
        get() = engine?.decodeSpeed() ?: 0f
}
