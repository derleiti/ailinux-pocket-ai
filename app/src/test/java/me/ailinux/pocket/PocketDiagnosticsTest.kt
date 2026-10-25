package me.ailinux.pocket

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PocketDiagnosticsTest {
    @Test fun eventNeverStoresUserPromptServerReceiptOrCredentials() {
        val bad = "Bearer secret-token https://api.example.com/mcp?key=SECRET"
        val event = PocketDiagnostics.Event(
            1780000000L, "aabbccdd", PocketDiagnostics.newTrace(),
            PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DISPATCH,
            PocketDiagnostics.State.START, "GGUF", PocketDiagnostics.fingerprint(bad),
            count = 42, durationMs = 15
        )
        val encoded = event.json().toString()
        assertFalse(encoded.contains("Bearer"))
        assertFalse(encoded.contains("SECRET"))
        assertFalse(encoded.contains("https://"))
        assertFalse(encoded.contains("secret-token"))
        assertNotNull(PocketDiagnostics.parse(JSONObject(encoded)))
        assertTrue(event.line().contains("DISPATCH"))
        assertEquals(10, event.toolHash.length)
    }

    @Test fun invalidJsonAndUntrustedFieldsAreRejectedOrDiscarded() {
        assertNull(PocketDiagnostics.parse(JSONObject()
            .put("id","alice@example.com").put("trace","ffffffff")))
        val event = PocketDiagnostics.Event(
            1780000000L, "aabbccdd", "ddccbbaa",
            PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_ERROR,
            PocketDiagnostics.State.FAIL, backend = "https://secret?token=1234",
            toolHash = "not_a_hash", durationMs = Long.MAX_VALUE
        )
        val parsed = PocketDiagnostics.parse(event.json())!!
        assertEquals("", parsed.backend)
        assertEquals("", parsed.toolHash)
        assertEquals(86_400_000L, parsed.durationMs)
    }

    @Test fun errorsAreTypedWithoutIncludingOriginalMessages() {
        val forbidden = "Authorization: Bearer VERY_PRIVATE https://evil.test/a?token=abc"
        val error = IllegalStateException("No KV cache inputs found; " + forbidden)
        assertEquals(PocketDiagnostics.ErrorCode.MODEL_KV,
            PocketDiagnostics.classify(error))
        assertFalse(PocketDiagnostics.publicError(error).contains("VERY_PRIVATE"))
        assertEquals(PocketDiagnostics.ErrorCode.TENSOR_BACKEND,
            PocketDiagnostics.classify(Exception("GOOGLE_TENSOR failed to create engine: NOT_FOUND")))
        assertEquals(PocketDiagnostics.ErrorCode.TIMEOUT,
            PocketDiagnostics.classify(Exception("MCP tools/call timeout")))
        assertEquals(PocketDiagnostics.ErrorCode.HTTP_AUTH,
            PocketDiagnostics.classify(Exception("HTTP 401 unauthorized")))
    }

    @Test fun nativeInferenceErrorsCarryElapsedTimeWithoutPromptOrExceptionDetails() {
        val event = PocketDiagnostics.Event(
            1780000000000L, "ccddeeff", "aa112233",
            PocketDiagnostics.Domain.LITERT, PocketDiagnostics.Step.MODEL_ERROR,
            PocketDiagnostics.State.FAIL, durationMs = 720_000L,
            code = PocketDiagnostics.ErrorCode.TIMEOUT
        )
        val published = event.line()
        assertTrue(published.contains("ms=720000"))
        assertTrue(published.contains("error=TIMEOUT"))
        val parsed = PocketDiagnostics.parse(event.json())!!
        assertEquals(720_000L, parsed.durationMs)
        val main = java.io.File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()
        val generator = main.substringAfter("private fun sendLiteRtMessage(")
            .substringBefore("private fun exportModel(")
        assertTrue(generator.contains("val inferenceStarted = SystemClock.elapsedRealtime()"))
        assertTrue(generator.contains(
            "durationMs = SystemClock.elapsedRealtime() - inferenceStarted"))
    }

    @Test fun memorySafetyGuardIsNotMisreportedAsUnknownNativeCrash() {
        val error = ModelMemoryGuardException(
            availableBytes = 1500L * 1024L * 1024L,
            requiredBytes = 2600L * 1024L * 1024L
        )
        assertEquals(PocketDiagnostics.ErrorCode.MEMORY, PocketDiagnostics.classify(error))
        assertEquals("ModelMemoryGuardException / MEMORY", PocketDiagnostics.publicError(error))
        assertFalse(PocketDiagnostics.publicError(error).contains("1500"))
        // An unrelated IOException must not be re-labelled as a RAM failure.
        assertNotEquals(PocketDiagnostics.ErrorCode.MEMORY,
            PocketDiagnostics.classify(java.io.IOException("Unspecified I/O error")))
    }

    @Test fun localDiscoveryFailuresAreTypedWithoutLoggingToolIds() {
        val expired = IllegalArgumentException("MCP-Katalog ist abgelaufen. Auto-Bridge neu verbinden.")
        val missing = IllegalArgumentException(
            "tool_id wurde noch nicht durch MCP-Suche angezeigt")
        val oversized = IllegalArgumentException(
            "Schema zu groß für lokales 4B-Modell; Tool manuell verwenden")
        assertEquals(PocketDiagnostics.ErrorCode.CATALOG_EXPIRED,
            PocketDiagnostics.classify(expired))
        assertEquals(PocketDiagnostics.ErrorCode.NOT_FOUND,
            PocketDiagnostics.classify(missing))
        assertEquals(PocketDiagnostics.ErrorCode.SCHEMA_TOO_LARGE,
            PocketDiagnostics.classify(oversized))
        assertEquals(PocketDiagnostics.ErrorCode.SCHEMA,
            PocketDiagnostics.classify(org.json.JSONException("invalid JSON key")))
        val event = PocketDiagnostics.Event(
            1780000000L, "aabbccdd", "11223344",
            PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.DETAILS,
            PocketDiagnostics.State.FAIL, code = PocketDiagnostics.classify(missing))
        assertEquals(PocketDiagnostics.ErrorCode.NOT_FOUND, event.code)
        assertFalse(event.json().toString().contains("tool_id"))
        assertFalse(event.line().contains("durch MCP-Suche"))
    }

    @Test fun toolErrorsInputRequiredAndSuccessAreDifferentStates() {
        val complete = JSONObject().put("resultType","complete").put("isError",false)
        val denied = JSONObject().put("resultType","complete").put("isError",true)
        val pending = JSONObject().put("resultType","input_required")
        val unsupported = JSONObject().put("resultType","future_unknown")
        assertEquals(PocketDiagnostics.Step.SERVER_RESULT,
            PocketDiagnostics.resultPhase(complete).first)
        assertEquals(PocketDiagnostics.Step.REMOTE_ERROR,
            PocketDiagnostics.resultPhase(denied).first)
        assertEquals(PocketDiagnostics.Step.INPUT_REQUIRED,
            PocketDiagnostics.resultPhase(pending).first)
        assertEquals(PocketDiagnostics.State.INFO,
            PocketDiagnostics.resultPhase(pending).second)
        assertEquals(PocketDiagnostics.State.FAIL,
            PocketDiagnostics.resultPhase(unsupported).second)
    }

    @Test fun denseEventTimestampsPreserveCausalOrder() {
        val one = PocketDiagnostics.nextTimestamp()
        val two = PocketDiagnostics.nextTimestamp()
        val three = PocketDiagnostics.nextTimestamp()
        assertTrue(two > one)
        assertTrue(three > two)
    }

    @Test fun latestEventsAppearFirstAndRemainVisibleInShortAccessibilityPreview() {
        val older = PocketDiagnostics.Event(1780000000L, "aabbccdd", "11111111",
            PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.SEARCH,
            PocketDiagnostics.State.OK)
        val newer = PocketDiagnostics.Event(1780000030L, "ddeeff00", "22222222",
            PocketDiagnostics.Domain.MCP, PocketDiagnostics.Step.SERVER_RESULT,
            PocketDiagnostics.State.OK)
        val text = PocketDiagnostics.recentLines(listOf(older, newer))
        assertTrue(text.indexOf("SERVER_RESULT") < text.indexOf("SEARCH"))
        assertTrue(PocketDiagnostics.recentLines(listOf(older,newer),130)
            .contains("SERVER_RESULT"))
        assertFalse(PocketDiagnostics.recentLines(listOf(older,newer),0).isNotEmpty())
    }

    @Test fun discoveryIsNotAConfirmedMcpServerReceipt() {
        val search = PocketDiagnostics.Event(1780000000L,"aabbccdd","ddccbbaa",
            PocketDiagnostics.Domain.MCP,PocketDiagnostics.Step.SEARCH,PocketDiagnostics.State.OK)
        assertFalse(search.line().contains("SERVER_RESULT"))
        assertTrue(search.copy(step = PocketDiagnostics.Step.SERVER_RESULT).line()
            .contains("SERVER_RESULT"))
    }
}
