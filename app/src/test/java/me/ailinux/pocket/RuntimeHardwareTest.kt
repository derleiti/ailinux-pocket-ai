package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class RuntimeHardwareTest {
    @Test fun pixel10EligibleOnlyForCompiledLiteRtModel() {
        assertEquals(
            listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR,
                RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU),
            RuntimeHardware.candidates("Google", "Pixel 10 Pro", "Tensor G5")
        )
    }

    @Test fun googleTensorDispatchIsAnExplicitApkInvariant() {
        val verifier = java.io.File("../scripts/verify-apk.sh").readText()
        assertTrue(verifier.contains(
            "lib/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
        ))
        val binary = java.io.File(
            "src/main/jniLibs/arm64-v8a/libLiteRtDispatch_GoogleTensor.so"
        )
        assertTrue(binary.isFile && binary.length() > 100_000)
        assertTrue(java.io.File("../third_party/google_tensor_dispatch/LICENSE").isFile)
    }

    @Test fun gpuNamedModelSkipsExpensiveTensorAttempt() {
        val all = RuntimeHardware.candidates("Google", "Pixel 10 Pro", "Tensor G5")
        assertEquals(listOf(RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU),
            RuntimeHardware.forModelFile("gemma-4-E2B-it-gpu.litertlm", all))
        assertEquals(all, RuntimeHardware.forModelFile("generic.litertlm", all))
        assertEquals(listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR),
            RuntimeHardware.forModelFile("gemma-4-E2B-npu.litertlm", all))
        assertEquals(listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR),
            RuntimeHardware.forModelFile("qwen-mixed-4b_Google_Tensor_G5.litertlm", all))
    }

    @Test fun upstreamQwen3CommunityFilesSkipUnverifiedTensorBackend() {
        val available = RuntimeHardware.candidates("Google", "Pixel 10", "Tensor G5")
        val expected = listOf(RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU)
        assertEquals(expected, RuntimeHardware.forModelFile(
            "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm", available))
        assertEquals(expected, RuntimeHardware.forModelFile(
            "Qwen3_1.7B.litertlm", available))
        // Only the allowlisted upstream artifacts skip Google Tensor.
        assertEquals(available, RuntimeHardware.forModelFile("another-qwen.litertlm", available))
        assertEquals(listOf(RuntimeHardware.Attempt.GOOGLE_TENSOR),
            RuntimeHardware.forModelFile("gemma-4-E2B-it_Google_Tensor_G5.litertlm", available))
    }

    @Test fun genericAndroidDoesNotAssumeVendorTpu() {
        assertEquals(listOf(RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU),
            RuntimeHardware.candidates("Samsung", "Galaxy S26", "Exynos"))
        assertEquals(listOf(RuntimeHardware.Attempt.GPU, RuntimeHardware.Attempt.CPU),
            RuntimeHardware.candidates("Other", "Pixel 10 clone", "Tensor G5"))
    }

    @Test fun pixel11UsesSameSafeFallback() {
        assertEquals(RuntimeHardware.Attempt.GOOGLE_TENSOR,
            RuntimeHardware.candidates("Google", "Pixel 11", "Tensor G6")[0])
        assertEquals(RuntimeHardware.Attempt.CPU,
            RuntimeHardware.candidates("Google", "Pixel 11", "Tensor G6").last())
    }
}
