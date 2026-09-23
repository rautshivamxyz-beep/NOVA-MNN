package org.nova

/** Curated catalog of MNN-format models (Alibaba's taobao-mnn collection).
 * 100% offline after download. Smallest-first: fast models for budget
 * phones at the top. MNN models are multi-file repos (config.json +
 * weights), downloaded by ModelDownloader.downloadRepo.
 */
object ModelCatalog {

    data class Entry(
        val id: String,
        val name: String,
        val org: String,
        val params: String,
        val quant: String,
        val sizeBytes: Long,
        val minRamGb: Int,
        val notes: String,
        val url: String
    ) {
        val fileName: String
            get() = url.substringAfterLast('/').substringBefore('?')
    }

    val entries = listOf(
        Entry(
            "qwen25-0.5b-mnn", "Qwen 2.5 0.5B Instruct", "Alibaba", "0.5B", "MNN 4-bit",
            550_000_000L /* ~0.55 GB */, 2,
            "Fastest model - quick everyday questions.",
            "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN"
        ),
        Entry(
            "llama32-1b-mnn", "Llama 3.2 1B Instruct", "Meta", "1B", "MNN 4-bit",
            1_200_000_000L /* ~1.2 GB */, 2,
            "Same brain as the NOVA llama build - the true engine A/B test. Start here.",
            "https://huggingface.co/taobao-mnn/Llama-3.2-1B-Instruct-MNN"
        ),
        Entry(
            "qwen25-1.5b-mnn", "Qwen 2.5 1.5B Instruct", "Alibaba", "1.5B", "MNN 4-bit",
            1_100_000_000L /* ~1.1 GB */, 3,
            "The quality pick for study, documents and quizzes.",
            "https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN"
        ),
        Entry(
            "qwen25-3b-mnn", "Qwen 2.5 3B Instruct", "Alibaba", "3B", "MNN 4-bit",
            2_000_000_000L /* ~2.0 GB */, 4,
            "Strong multilingual model if RAM allows.",
            "https://huggingface.co/taobao-mnn/Qwen2.5-3B-Instruct-MNN"
        )
    )

    /**
     * App-private directory where downloaded / imported models live.
     */
    fun modelsDir(context: android.content.Context): java.io.File {
        val ext = context.getExternalFilesDir(null)
        val dir = if (ext != null) java.io.File(ext, "models") else java.io.File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun labelFor(file: java.io.File): String {
        val n = file.name.removeSuffix(".gguf")
        return n.replace('-', ' ').replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }

    /** Total size of a downloaded model - single file or model directory. */
    fun sizeOf(f: java.io.File): Long =
        if (f.isFile) f.length()
        else f.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
}
