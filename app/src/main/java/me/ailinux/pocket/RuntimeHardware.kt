package me.ailinux.pocket

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Backend suitability is a *candidate*, never a claim of successful TPU execution. */
internal object RuntimeHardware {
    enum class Attempt { GOOGLE_TENSOR, GPU, CPU }

    fun candidates(manufacturer: String, model: String, soc: String): List<Attempt> {
        val google = manufacturer.equals("google", true)
        val pixelG5G6 = google && (
            model.startsWith("Pixel 10", true) ||
                model.startsWith("Pixel 11", true) ||
                soc.contains("Tensor G5", true) || soc.contains("Tensor G6", true)
            )
        return if (pixelG5G6) listOf(Attempt.GOOGLE_TENSOR, Attempt.GPU, Attempt.CPU)
        else listOf(Attempt.GPU, Attempt.CPU)
    }

    fun candidatesOnDevice(): List<Attempt> =
        candidates(Build.MANUFACTURER, Build.MODEL, Build.SOC_MODEL)

    fun diagnostics(context: Context): String {
        val features = context.packageManager.systemAvailableFeatures
        val vulkan = features.firstOrNull {
            it.name == PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL
        }?.version
        val order = candidatesOnDevice().joinToString(" → ") { when (it) {
            Attempt.GOOGLE_TENSOR -> "Google Tensor TPU (nur bei kompatiblem .litertlm)"
            Attempt.GPU -> "GPU (OpenCL, sofern Treiber/Modell kompatibel)"
            Attempt.CPU -> "CPU (Fallback)"
        } }
        return "Android-Gerät: " + Build.MANUFACTURER + " " + Build.MODEL +
            "\nSoC: " + Build.SOC_MODEL +
            "\nABI: " + Build.SUPPORTED_ABIS.joinToString(", ") +
            "\nVulkan-Hardware-Level: " + (vulkan?.toString() ?: "nicht erkannt") +
            "\n\nGGUF / Spark: llama.cpp CPU (stabil)." +
            "\nLiteRT-LM: " + order +
            "\n\nHardware-Erkennung ist noch KEIN TPU-Benchmark. " +
            "Die App zeigt das tatsächlich initialisierte LiteRT-Backend nach Modellstart."
    }
}
