package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class ModelLoadSafetyTest {
    private val gib = 1024L * 1024 * 1024

    @Test fun flagsLargeQ8Models() {
        assertTrue(ModelLoadSafety.requiresConfirmation(4L * gib, 10L * gib))
    }
    @Test fun allowsSmallQuantizedModel() {
        assertFalse(ModelLoadSafety.requiresConfirmation(gib / 2, 6L * gib))
    }
    @Test fun preventsNearExhaustion() {
        assertTrue(ModelLoadSafety.requiresConfirmation(2L * gib, 2L * gib))
    }
    @Test fun handlesUnknownSize() {
        assertFalse(ModelLoadSafety.requiresConfirmation(0, 7L * gib))
    }
    @Test fun exposesReasonForWarning() {
        val explanation = ModelLoadSafety.explain(4L * gib, 8L * gib)
        assertTrue(explanation.contains("RAM"))
        assertTrue(explanation.contains("Q4"))
    }
}
