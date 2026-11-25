package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class ModelCompatibilityTest {
    @Test fun mmprojVisionProjectorsAreNotStandaloneTextModels() {
        for (name in listOf(
            "mmproj-gemma-4-E4B-it-BF16.gguf",
            "mmproj-gemma-3-4b-it-Q4_K_M.gguf",
            "gemma_4.vision_projector.gguf"
        )) {
            assertTrue(name, ModelCompatibility.isProjectorFilename(name))
            val classified = ModelCompatibility.classify(null, name)
            assertTrue(name, classified.projectorOnly)
            assertFalse(name, classified.canAttemptTextInference)
            assertTrue(classified.incompatibleReason.orEmpty().contains("Visionprojektor"))
        }
        assertFalse(ModelCompatibility.isProjectorFilename("gemma4-vision-4b.gguf"))
        assertFalse(ModelCompatibility.isProjectorFilename("Qwen3.5-VL-2B-Q4.gguf"))
        assertFalse(ModelCompatibility.isProjectorFilename("mmproj-checkpoint.litertlm"))
    }

    @Test fun explicitlyTypedVisionProjectorsAreRejectedWithoutFilenameMarkers() {
        assertTrue(ModelCompatibility.classify("clip").projectorOnly)
        assertTrue(ModelCompatibility.classify("siglip2", "hidden.gguf").projectorOnly)
        assertTrue(ModelCompatibility.classify("llama", "hidden.gguf", "mmproj").projectorOnly)
        assertFalse(ModelCompatibility.classify("gemma4",
            "Gemma-4-Vision-4B.gguf").projectorOnly)
        assertTrue(ModelCompatibility.classify("qwen3",
            "Qwen3.5-VL-2B-Q4_K_M.gguf").canAttemptTextInference)
    }

    @Test fun qwenImage21IsRejected() {
        val result = ModelCompatibility.classify("qwen_image21", "qwen-image-2.1-UC-Q5_K_M.gguf")
        assertFalse(result.canAttemptTextInference)
        assertTrue(result.incompatibleReason.orEmpty().contains("Diffusionsmodell"))
    }
    @Test fun filenameFallbackDetectsQwenImage() {
        assertFalse(ModelCompatibility.classify(null, "qwen-image-2.1-UC-Q5_K_M.gguf").canAttemptTextInference)
    }
    @Test fun qwen2TextIsAccepted() {
        assertTrue(ModelCompatibility.classify("qwen2", "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf").canAttemptTextInference)
    }
    @Test fun qwen3TextIsAccepted() {
        assertTrue(ModelCompatibility.classify("qwen3").canAttemptTextInference)
    }
    @Test fun unknownArchitecturesAreNotRejectedWithoutEvidence() {
        assertTrue(ModelCompatibility.classify("future_text_model").canAttemptTextInference)
    }
    @Test fun typeBasedDiffusionIsRejected() {
        assertFalse(ModelCompatibility.classify(null, "abc.gguf", "text-to-image").canAttemptTextInference)
    }
}
