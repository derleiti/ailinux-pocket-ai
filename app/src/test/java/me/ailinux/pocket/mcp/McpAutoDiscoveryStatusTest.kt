package me.ailinux.pocket.mcp

import org.junit.Assert.*
import org.junit.Test
import me.ailinux.pocket.PocketDiagnostics
import java.io.File

class McpAutoDiscoveryStatusTest {
    @Test fun onlySourceNumberAndHttpCodeAppearInError() {
        assertEquals("Quelle #2: HTTP 503", McpAutoDiscoveryStatus.safeFailure(
            2, 503, PocketDiagnostics.ErrorCode.HTTP_FAILURE))
        assertEquals("Quelle #1: HTTP_AUTH", McpAutoDiscoveryStatus.safeFailure(
            1, null, PocketDiagnostics.ErrorCode.HTTP_AUTH))
        assertEquals("Quelle #3: NETWORK", McpAutoDiscoveryStatus.safeFailure(
            3, 0, PocketDiagnostics.ErrorCode.NETWORK))
    }

    @Test fun invalidOrdinalsMustNotBeRenderedAsKnownSource() {
        assertThrows(IllegalArgumentException::class.java) {
            McpAutoDiscoveryStatus.safeFailure(0, 500, PocketDiagnostics.ErrorCode.HTTP_FAILURE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            McpAutoDiscoveryStatus.safeFailure(
                McpProgressiveBridge.MAX_SERVERS + 1,
                500, PocketDiagnostics.ErrorCode.HTTP_FAILURE)
        }
    }

    @Test fun failureInOneServerDoesNotDiscardOtherLiveSources() {
        val source = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        val chooser = source.substringAfter("private fun showAutoServerChooser")
            .substringBefore("private fun chooseServer")
        assertTrue(chooser.contains("chosen.mapIndexedNotNull"))
        assertTrue(chooser.contains("sourceNumber = index + 1"))
        assertTrue(chooser.contains("PocketDiagnostics.classify(problem)"))
        assertTrue(chooser.contains("count = sourceNumber"))
        assertTrue(chooser.contains("offline.add(McpAutoDiscoveryStatus.safeFailure("))
        assertTrue(chooser.contains("require(sources.isNotEmpty())"))
        assertTrue(chooser.contains("sources.size +"))
        assertTrue(chooser.contains("AUTO-MCP nur teilweise verbunden"))
        assertTrue(chooser.contains("tools/call-Wiederholung"))
    }
}
