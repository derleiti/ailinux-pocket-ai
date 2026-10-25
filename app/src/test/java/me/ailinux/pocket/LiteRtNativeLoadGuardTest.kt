package me.ailinux.pocket

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LiteRtNativeLoadGuardTest {
    private fun isIllegalState(error: Exception): Boolean = error is IllegalStateException

    @Test fun nativeCancellationPropagatesWithoutTryingAnotherBackend() {
        val cancelled = CancellationException("stop model load")
        val closes = AtomicInteger()
        val thrown = assertThrows(CancellationException::class.java) {
            LiteRtNativeLoadGuard.rethrowIfCancelled(cancelled) { closes.incrementAndGet() }
        }
        assertSame(cancelled, thrown)
        assertEquals(1, closes.get())
        assertTrue(isIllegalState(cancelled))
    }

    @Test fun normalBackendFailureDoesNotTriggerCancellationCleanup() {
        val closes = AtomicInteger()
        LiteRtNativeLoadGuard.rethrowIfCancelled(IllegalStateException("unsupported")) {
            closes.incrementAndGet()
        }
        assertEquals(0, closes.get())
    }

    @Test fun cleanupExceptionDoesNotHideOriginalCancellation() {
        val cancelled = CancellationException("native start cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            LiteRtNativeLoadGuard.rethrowIfCancelled(cancelled) {
                throw IllegalStateException("cleanup failed")
            }
        }
        assertSame(cancelled, thrown)
    }

    @Test fun runtimeChecksCancellationBeforeAndAfterNativeInitialize() {
        val runtime = File("src/main/java/me/ailinux/pocket/LiteRtRuntime.kt").readText()
            .substringAfter("suspend fun load(context: Context")
        val beforeAttempt = runtime.indexOf("for (attempt in choices)")
        val beforeInitialize = runtime.indexOf("candidate.initialize()")
        val afterInitialize = runtime.indexOf("initialized = true")
        assertTrue(beforeAttempt >= 0)
        assertTrue(runtime.indexOf("currentCoroutineContext().ensureActive()", beforeAttempt) in
            (beforeAttempt + 1) until beforeInitialize)
        assertTrue(runtime.indexOf("currentCoroutineContext().ensureActive()", afterInitialize) >
            afterInitialize)
        assertTrue(runtime.contains("LiteRtNativeLoadGuard.rethrowIfCancelled(problem)"))
    }
}
