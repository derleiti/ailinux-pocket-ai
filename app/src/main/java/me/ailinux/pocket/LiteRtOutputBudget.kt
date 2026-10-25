package me.ailinux.pocket

/**
 * LiteRT-LM 0.18.0 ConversationConfig.maxOutputToken is a native generation
 * bound, not a wall-clock timeout or an interrupt for a stuck JNI call.
 *
 * Native MCP can require several independent model turns; bound EACH turn,
 * without changing the separate host approval/dispatch safeguards.
 */
internal object LiteRtOutputBudget {
    const val MAX_MCP_TOKENS = 256
    const val MAX_CHAT_TOKENS = 768

    fun forSession(mcpConnected: Boolean): Int =
        if (mcpConnected) MAX_MCP_TOKENS else MAX_CHAT_TOKENS
}
