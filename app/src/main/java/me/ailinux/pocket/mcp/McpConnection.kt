package me.ailinux.pocket.mcp

import org.json.JSONObject

/**
 * Dispatch by explicit transport choice. AUTO recognises the conventional
 * 2024 HTTP+SSE endpoint suffix; otherwise uses modern Streamable HTTP.
 * Authentication errors never trigger a silent downgrade.
 */
object McpConnection {
    private fun legacy(profile: McpProfile): Boolean = when (profile.transport) {
        McpTransport.LEGACY_SSE -> true
        McpTransport.STREAMABLE_HTTP -> false
        McpTransport.AUTO -> profile.endpoint.substringBefore('?')
            .trimEnd('/').endsWith("/sse", ignoreCase = true)
    }

    fun inspect(profile: McpProfile): McpServerInfo {
        if (!legacy(profile)) return McpClient(profile).inspect()
        return McpLegacySseClient(profile).use {
            it.open()
            it.inspect()
        }
    }

    fun callTool(profile: McpProfile, tool: McpTool, params: JSONObject): JSONObject {
        if (!legacy(profile)) return McpClient(profile)
            .callTool(tool.name, params, tool.schema)
        return McpLegacySseClient(profile).use {
            it.open()
            it.callTool(tool.name, params)
        }
    }
}
