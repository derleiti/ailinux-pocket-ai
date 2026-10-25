package me.ailinux.pocket.mcp

import me.ailinux.pocket.PocketDiagnostics

/**
 * Per-source error evidence for a user-initiated MCP catalogue refresh.
 * Never include endpoints, credentials, profile names or raw server bodies.
 */
internal object McpAutoDiscoveryStatus {
    fun safeFailure(
        sourceOrdinal: Int,
        httpStatus: Int?,
        category: PocketDiagnostics.ErrorCode
    ): String {
        require(sourceOrdinal in 1..McpProgressiveBridge.MAX_SERVERS)
        val reason = if (httpStatus != null && httpStatus in 400..599)
            "HTTP " + httpStatus
        else category.name
        return "Quelle #" + sourceOrdinal + ": " + reason
    }
}
