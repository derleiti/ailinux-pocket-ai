package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class ModelCompatibilityTest {
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
