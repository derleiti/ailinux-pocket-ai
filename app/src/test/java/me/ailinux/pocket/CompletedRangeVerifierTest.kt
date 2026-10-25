package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

/**
 * RFC 9110 416 may omit Content-Range and ETag on a CDN.
 * These tests never call the network; authenticated bytes must be verified
 * by SHA-256 or a fresh strong identity plus exact length before publish.
 */
class CompletedRangeVerifierTest {
    private val expected = "362ec1c906a49bbe1e68b118fdb789b70a713063b00ac914043cbda03bcf3d9a"

    @Test fun knownShaAllowsEmpty416HeadersWithFullHashVerificationLater() {
        assertEquals(expected, ModelStore.completedRangeProof(
            2056729520L, null, null, null, expected, null))
    }

    @Test fun trustedHuggingFaceLinkedHashAndExactSizeCanRescueCompletedPart() {
        val head = ModelStore.CompletedRangeEvidence(
            length = 2056729520L, linkedSha256 = expected)
        assertEquals(expected, ModelStore.completedRangeProof(
            2056729520L, null, null, null, null, head))
    }

    @Test fun truncatedPartCannotBePublishedBasedOnTrustedHashHeaderOnly() {
        val head = ModelStore.CompletedRangeEvidence(
            length = 2056729520L, linkedSha256 = expected)
        assertThrows(IllegalArgumentException::class.java) {
            ModelStore.completedRangeProof(
                2000000000L, null, null, null, null, head)
        }
    }

    @Test fun explicitConflictingContentRangeAlwaysBlocksEvenWithPinnedHash() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelStore.completedRangeProof(2056729520L,
                "bytes */2056729521", null, null, expected, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelStore.completedRangeProof(2056729520L,
                "bytes 0-10/100", null, null, expected, null)
        }
    }

    @Test fun freshStrongEtagAndExactSizeCanProveIdentityWithoutHash() {
        val head = ModelStore.CompletedRangeEvidence(
            length = 1500L, strongEtag = "\"stable-2026\"")
        assertNull(ModelStore.completedRangeProof(
            1500L, null, null, "\"stable-2026\"", null, head))
        assertNull(ModelStore.completedRangeProof(
            1500L, "bytes */1500", "\"stable-2026\"",
            "\"stable-2026\"", null, null))
    }

    @Test fun weakChangedOrMissingEtagNeverCompletesFromLengthAlone() {
        val weak = ModelStore.CompletedRangeEvidence(
            length = 1500L, strongEtag = "W/\"maybe\"")
        val changed = ModelStore.CompletedRangeEvidence(
            length = 1500L, strongEtag = "\"other\"")
        for (head in listOf(weak, changed, ModelStore.CompletedRangeEvidence(1500L))) {
            assertThrows(IllegalArgumentException::class.java) {
                ModelStore.completedRangeProof(1500L, null, null, "\"stable\"", null, head)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelStore.completedRangeProof(1500L, null, null, null, null, null)
        }
    }

    @Test fun malformedSourceHashNotAcceptedAsProof() {
        val head = ModelStore.CompletedRangeEvidence(
            length = 1500L, linkedSha256 = "not-a-digest")
        assertThrows(IllegalArgumentException::class.java) {
            ModelStore.completedRangeProof(1500L, null, null, null, null, head)
        }
    }
}
