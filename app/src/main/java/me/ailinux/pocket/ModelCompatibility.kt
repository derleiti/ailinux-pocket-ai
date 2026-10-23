package me.ailinux.pocket

import com.arm.aichat.gguf.GgufMetadataReader
import java.io.File
import java.util.Locale

/**
 * Preflight uses the official GGUF metadata parser without loading tensor weights.
 * GGUF is a container and not all GGUF architectures are chat models.
 */
object ModelCompatibility {
    data class Inspection(
        val architecture: String?,
        val name: String?,
        val modelType: String?,
        val inspectionWarning: String? = null,
        val incompatibleReason: String? = null
    ) {
        val canAttemptTextInference: Boolean get() = incompatibleReason == null

        fun loaderError(): String =
            "llama.cpp konnte das Modell nicht laden. " +
            "Architektur: ${architecture ?: "nicht ermittelt"}. " +
            "Mögliche Ursachen: nicht unterstützte Architektur, " +
            "unvollständige/beschädigte Datei oder zu wenig Arbeitsspeicher. " +
            "Zum Test bitte ein kleines Qwen2.5-Instruct-Q4_K_M-GGUF verwenden."
    }

    fun classify(architecture: String?, name: String? = null, type: String? = null): Inspection {
        val a = architecture?.lowercase(Locale.ROOT)?.trim().orEmpty()
        val n = name?.lowercase(Locale.ROOT)?.trim().orEmpty()
        val t = type?.lowercase(Locale.ROOT)?.trim().orEmpty()
        val diffusionArchitectures = setOf(
            "qwen_image", "qwen_image21", "qwen_image2", "flux", "flux2", "sd3",
            "stable_diffusion", "wan", "hunyuan_video", "z_image", "chroma",
            "hidream", "lumina", "auraflow"
        )
        val clearlyImage = a in diffusionArchitectures ||
            a.startsWith("qwen_image") ||
            a.startsWith("stable_diffusion") ||
            (a.isBlank() && Regex("""(^|[-_])qwen[-_]image(?:[-_.]|$)""").containsMatchIn(n))
        val imageType = t in setOf("diffusion", "image-generation", "text-to-image")
        val reason = if (clearlyImage || imageType) {
            val kind = architecture ?: name ?: "unbekannt"
            "Dieses GGUF ist ein Bildgenerierungs-/Diffusionsmodell ($kind), " +
            "kein Chat-Sprachmodell. Pocket AI verwendet llama.cpp für Textmodelle. " +
            "Qwen-Image benötigt zum Beispiel ComfyUI mit passendem GGUF-Loader, " +
            "Text-Encoder und VAE. Für Pocket AI ein Instruct-Textmodell verwenden."
        } else null
        return Inspection(architecture, name, type, incompatibleReason = reason)
    }

    suspend fun inspect(file: File): Inspection {
        ModelStore.validate(file)
        return try {
            val metadata = file.inputStream().buffered().use { input ->
                GgufMetadataReader.create().readStructuredMetadata(input)
            }
            val architecture = metadata.architecture?.architecture
            val type = metadata.additional?.type
            val parsedName = metadata.basic.nameLabel ?: metadata.basic.name ?: file.name
            val primary = classify(architecture, parsedName, type)
            if (primary.canAttemptTextInference) {
                val filenameCheck = classify(architecture, file.name, type)
                if (!filenameCheck.canAttemptTextInference) filenameCheck else primary
            } else primary
        } catch (error: Exception) {
            // A newer GGUF format might be unreadable by the metadata-only parser.
            // Allow the native engine to try, except unmistakable Qwen Image filenames.
            classify(null, file.name).copy(
                inspectionWarning = error.javaClass.simpleName + ": " + (error.message ?: "unknown")
            )
        }
    }
}
