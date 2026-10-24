package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test

class HuggingFaceDiscoveryTest {
    @Test fun proxyResultsAreValidatedAndIncludeRealSmallFile() {
        val json = """{
          "origin":"huggingface.co/api/models",
          "results":[
            {"id":"bartowski/SmolLM2-360M-Instruct-GGUF", "downloads":900,
             "likes":23, "recommended":{"filename":"SmolLM2-Q4_K_M.gguf","bytes":270590880}},
            {"id":"../private", "downloads":1000},
            {"id":"bartowski/SmolLM2-360M-Instruct-GGUF","downloads":50}
          ]}"""
        val parsed = HuggingFaceDiscovery.parseProxyResponse(json)
        assertEquals(1, parsed.size)
        assertEquals("SmolLM2-Q4_K_M.gguf", parsed.single().suggestedFile)
        assertEquals(270590880L, parsed.single().suggestedBytes)
        assertEquals(900L, parsed.single().downloads)
    }

    @Test fun rejectsUntrustedOriginAndTraversalOrInjectedFilenames() {
        assertThrows(IllegalArgumentException::class.java) {
            HuggingFaceDiscovery.parseProxyResponse("""{"origin":"evil","results":[]}""")
        }
        val parsed = HuggingFaceDiscovery.parseProxyResponse(
            """{"origin":"huggingface.co/api/models","results":[
              {"id":"user/safe","recommended":{"filename":"../../spoof.gguf","bytes":120}},
              {"id":"user/../bad"}
            ]}"""
        )
        assertEquals(1, parsed.size)
        assertNull(parsed.single().suggestedFile)
    }

    @Test fun explicitJsonNullPipelineIsTreatedAsUnspecifiedOnAndroid() {
        val source = java.io.File(
            "src/main/java/me/ailinux/pocket/HuggingFaceDiscovery.kt"
        ).readText()
        // Android org.json optString(JSONObject.NULL) and the JVM test
        // org.json version differ. Pin explicit null normalization in source.
        assertTrue(source.contains("item.isNull(\"pipeline_tag\")"))
        val json = """[
          {"id":"litert-community/gemma-4-E2B-it-litert-lm",
           "gated":false,"private":false,"pipeline_tag":null,
           "tags":["litert-lm"],"downloads":900}
        ]"""
        assertEquals(1, HuggingFaceDiscovery.parseHubSearch(json, 5, "litertlm").size)
    }

    @Test fun directHubSearchRecognizesOfficialLiteRtLmTag() {
        val data = """[
          {"id":"litert-community/gemma-4-E2B-it-litert-lm", "gated":false,
           "pipeline_tag":null,"tags":["litert-lm","gemma-4"],"downloads":2048},
          {"id":"gguf-community/gemma-4-E2B-it", "gated":false,
           "tags":["gguf"],"downloads":4000}
        ]"""
        val lite = HuggingFaceDiscovery.parseHubSearch(data, 10, "litertlm")
        assertEquals(1, lite.size)
        assertEquals("litertlm", lite.single().format)
        assertEquals(2048L, lite.single().downloads)
        assertEquals(1, HuggingFaceDiscovery.parseHubSearch(data, 10, "gguf").size)
    }

    @Test fun proxyExplicitlyLabelsTpuModelFormat() {
        val json = """{"origin":"huggingface.co/api/models","results":[
          {"id":"litert-community/gemma-4-E2B-it-litert-lm",
           "format":"LiteRT-LM","downloads":13}
        ]}"""
        val parsed = HuggingFaceDiscovery.parseProxyResponse(json)
        assertEquals("litertlm", parsed.single().format)
    }

    @Test fun directHubResultsExcludeGatedPrivateAndNonTextModels() {
        val data = """[
          {"id":"safe/Public-GGUF","gated":false,"private":false,
           "pipeline_tag":"text-generation","tags":["gguf"],"downloads":17},
          {"id":"safe/Gated-GGUF","gated":"auto",
           "pipeline_tag":"text-generation","tags":["gguf"]},
          {"id":"safe/Private-GGUF","private":true,
           "pipeline_tag":"text-generation","tags":["gguf"]},
          {"id":"safe/Image-GGUF","pipeline_tag":"text-to-image","tags":["gguf"]},
          {"id":"safe/noGGUF","tags":["safetensors"]}
        ]"""
        val result = HuggingFaceDiscovery.parseHubSearch(data, 20)
        assertEquals(listOf("safe/Public-GGUF"), result.map { it.id })
    }
}
