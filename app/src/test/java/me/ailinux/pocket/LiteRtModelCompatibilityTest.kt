package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiteRtModelCompatibilityTest {
    private val int4 = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm"
    private val int8 = "Qwen3_1.7B.litertlm"

    @Test fun upstreamPublishedQwen3RequiresRuntime018OrNewer() {
        for (name in listOf(int4, int8)) {
            assertEquals("0.18.0", LiteRtModelCompatibility.minimumRuntimeVersion(name))
            assertFalse(LiteRtModelCompatibility.supportsVersion(name, "0.12.0"))
            assertFalse(LiteRtModelCompatibility.supportsVersion(name, "0.17.99"))
            assertFalse(LiteRtModelCompatibility.supportsVersion(name, "invalid"))
            assertTrue(LiteRtModelCompatibility.supportsVersion(name, "0.18.0"))
            assertTrue(LiteRtModelCompatibility.supportsVersion(name, "0.19.1"))
            assertTrue(LiteRtModelCompatibility.isKnownGpuGraph(name))
        }
    }

    @Test fun knownMismatchRejectedBeforeNativeInitialization() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            LiteRtModelCompatibility.requireSupported(int4, "0.12.0")
        }
        assertTrue(e.message.orEmpty().contains("0.18.0"))
        assertTrue(e.message.orEmpty().contains("nicht gestartet oder gelöscht"))
        LiteRtModelCompatibility.requireSupported(int4, "0.18.0")
        val loader = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
            .substringAfter("private fun loadLiteRtModel(")
            .substringBefore("private fun showMcpAgentMenu()")
        assertTrue(loader.indexOf("LiteRtModelCompatibility.requireSupported(") <
            loader.indexOf("previousModelUnloaded = true"))
        val runtime = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt").readText()
        assertTrue(runtime.contains("LiteRtModelCompatibility.requireSupported("))
    }

    @Test fun unknownModelsRemainUnclassifiedWithoutInventingRequirements() {
        assertNull(LiteRtModelCompatibility.minimumRuntimeVersion("gemma-4-E2B-it_Google_Tensor_G5.litertlm"))
        assertFalse(LiteRtModelCompatibility.isKnownGpuGraph("qwen-mixed-4b_Google_Tensor_G5.litertlm"))
        assertTrue(LiteRtModelCompatibility.supportsVersion("unknown.litertlm", "0.12.0"))
        assertFalse(LiteRtModelCompatibility.isKnownGpuGraph("Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm.bak"))
    }

    @Test fun dependencyVersionMatchesRuntimeGateBuildConfig() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("val liteRtLmVersion ="))
        assertTrue(gradle.contains("litertlm-android:\$liteRtLmVersion"))
        assertTrue(gradle.contains("LITERT_LM_VERSION"))
        assertTrue(LiteRtModelCompatibility.supportsVersion(int4, BuildConfig.LITERT_LM_VERSION))
    }
}
