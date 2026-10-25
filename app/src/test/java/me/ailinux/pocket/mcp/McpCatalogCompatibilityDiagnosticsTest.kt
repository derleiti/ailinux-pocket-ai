package me.ailinux.pocket.mcp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class McpCatalogCompatibilityDiagnosticsTest {
    @Test fun announcedDoesNotImplyLocallyCallableForSmallModel() {
        val state = McpProgressiveBridge.Run(listOf(
            McpProgressiveBridge.Source(
                "s1", "p", "https://api.ailinux.me/v1/mcp",
                "TriForce DEV", listOf(
                    McpTool("status","read", JSONObject().put("type","object")),
                    McpTool("invalid schema", "bad name",
                        JSONObject().put("type","object")),
                    McpTool("future_schema","unsupported",
                        JSONObject().put("type","object")
                            .put("properties",JSONObject())
                            .put("oneOf",org.json.JSONArray())),
                    McpTool("discord_connection_status","read",
                        JSONObject().put("type","object"))
                )
            )
        ))
        assertEquals(4, state.advertisedTools)
        assertEquals(2, state.totalTools)
        assertEquals(2, state.filteredTools)
        assertEquals(0, state.remoteCalls)
    }

    @Test fun diagnosticSeparatesSdkParserFailuresFromAppSchemaFailuresWithoutPayload() {
        val main = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val diagnostics = File("src/main/java/me/ailinux/pocket/PocketDiagnostics.kt").readText()
        assertTrue(main.contains("PocketDiagnostics.Step.NATIVE_RESPONSE_REJECTED"))
        assertTrue(main.contains("PocketDiagnostics.ErrorCode.NATIVE_RESPONSE_REJECTED"))
        assertTrue(main.contains("PocketDiagnostics.Step.CATALOG_FILTERED"))
        assertTrue(main.contains("snapshot?.filteredTools"))
        assertTrue(main.contains("tool = candidate?.name.orEmpty()"))
        assertTrue(diagnostics.contains("NATIVE_RESPONSE_REJECTED"))
        assertFalse(diagnostics.contains("put(\"arguments\""))
        assertFalse(diagnostics.contains("put(\"prompt\""))
    }
}
