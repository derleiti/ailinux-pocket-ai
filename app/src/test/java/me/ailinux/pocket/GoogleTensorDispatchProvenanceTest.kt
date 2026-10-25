package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** Prevent shipping an obsolete or substituted TPU dispatcher unnoticed. */
class GoogleTensorDispatchProvenanceTest {
    private val expected = "86df1958b1b2abd3b73ac2c52b7cc4ea06744526d220a9c620df856c2b3a0690"
    private val dispatcher = File("src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so")

    @Test fun npuDispatcherMatchesPinnedLiteRtLm018Source() {
        assertTrue("Verified Google Tensor dispatch JNI library missing", dispatcher.isFile)
        val bytes = dispatcher.readBytes()
        assertEquals(527472, bytes.size)
        // ELF magic, 64-bit little-endian, ET_DYN, EM_AARCH64 (183).
        assertArrayEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46), bytes.copyOfRange(0, 4))
        assertEquals(2, bytes[4].toInt())
        assertEquals(1, bytes[5].toInt())
        assertEquals(3, bytes[16].toInt())
        assertEquals(183, bytes[18].toInt() and 0xff)
        assertEquals(0, bytes[19].toInt() and 0xff)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, hash)
        assertTrue(String(bytes, StandardCharsets.ISO_8859_1)
            .contains("LiteRtDispatchGetApi"))
        assertEquals("0.18.0", BuildConfig.LITERT_LM_VERSION)
        val verifier = File("../scripts/verify-apk.sh").readText()
        assertTrue("APK release verifier must enforce the pinned dispatcher",
            verifier.contains("EXPECTED_DISPATCH_SHA=\"$expected\""))
    }

    @Test fun sourcePinAndBuildOnlyPatchAreDocumentedAndRetained() {
        val notes = File("../third_party/google_tensor_dispatch/README.md").readText()
        assertTrue(notes.contains(expected))
        assertTrue(notes.contains("26895c9fbcc25c43faa8c1a98cd1fd28951602c3"))
        assertTrue(notes.contains("b2f686e2ed4718fb84ec398a61dd59ca0f0aff27"))
        assertTrue(notes.contains("75d637c4de3cf60b464daf7ba85176f856779f3d2d6cc091c0392bb3776064ed"))
        val patch = File("../third_party/google_tensor_dispatch/patches/disable-unused-google-tensor-profiling-hooks.patch")
        assertTrue(patch.isFile)
        assertTrue(patch.readText().contains("-        \"//litert/vendors/google_tensor/hooks\""))
        assertTrue(File("../third_party/google_tensor_dispatch/LICENSE").isFile)
    }
}
