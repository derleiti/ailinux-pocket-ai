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

    @Test fun discoveryIsNotAConfirmedMcpServerReceipt() {
        val search = PocketDiagnostics.Event(1780000000L,"aabbccdd","ddccbbaa",
            PocketDiagnostics.Domain.MCP,PocketDiagnostics.Step.SEARCH,PocketDiagnostics.State.OK)
        assertFalse(search.line().contains("SERVER_RESULT"))
        assertTrue(search.copy(step = PocketDiagnostics.Step.SERVER_RESULT).line()
            .contains("SERVER_RESULT"))
    }
}
