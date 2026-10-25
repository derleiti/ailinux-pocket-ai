package me.ailinux.pocket

import java.util.Locale

/** Narrow upstream-published hints, not generic model-format inspection.
 * Renamed/unknown .litertlm models remain unverified and use existing rules.
 */
internal object LiteRtModelCompatibility {
    private const val QWEN3_MIN_VERSION = "0.18.0"
    private val qwen3CommunityFiles = setOf(
        "qwen3-1.7b_dynamic_wi4b32_afp32.litertlm",
        "qwen3_1.7b.litertlm"
    )

    fun isKnownGpuGraph(fileName: String): Boolean =
        fileName.lowercase(Locale.ROOT) in qwen3CommunityFiles

    fun minimumRuntimeVersion(fileName: String): String? =
        if (isKnownGpuGraph(fileName)) QWEN3_MIN_VERSION else null

    fun supportsVersion(fileName: String, runtimeVersion: String): Boolean {
        val minimum = minimumRuntimeVersion(fileName) ?: return true
        val found = parseVersion(runtimeVersion) ?: return false
        val required = parseVersion(minimum) ?: return false
        return found.zip(required).firstOrNull { (a, b) -> a != b }
            ?.let { (a, b) -> a > b } ?: true
    }

    fun requireSupported(fileName: String, runtimeVersion: String) {
        val minimum = minimumRuntimeVersion(fileName) ?: return
        require(supportsVersion(fileName, runtimeVersion)) {
            "Dieses Qwen3 LiteRT-Community-Modell benötigt LiteRT-LM mindestens " +
                minimum + "; installiert ist " + runtimeVersion.take(32) +
                ". Modell wurde nicht gestartet oder gelöscht."
        }
    }

    private fun parseVersion(value: String): List<Int>? {
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(value)) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }
}
