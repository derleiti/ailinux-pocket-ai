package me.ailinux.pocket.mcp

import org.json.JSONObject

/** Narrow encrypted token-store API; injectable for offline OAuth regression tests. */
interface McpTokenStore {
    fun upsert(profile: McpProfile)
    fun get(id: String): McpProfile?
    fun putPendingOAuth(pending: JSONObject)
    fun pendingOAuth(): JSONObject?
    fun clearPendingOAuth()
}
