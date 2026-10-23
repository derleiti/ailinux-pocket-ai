package me.ailinux.pocket.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression checks for the Android visible SETUP and OAuth redirect receiver. */
class McpSetupIntegrationTest {
    @Test fun settingsContainsVisibleServerListAndAddAction() {
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        val setup = layout.substringAfter("android:id=\"@+id/settingsPage\"")
        assertTrue(setup.contains("@+id/mcpServerList"))
        assertTrue(setup.contains("@+id/addMcpServerButton"))
        assertTrue(setup.contains("MCP VERBINDUNGEN"))
        val chat = layout.substringAfter("android:id=\"@+id/chatPage\"")
            .substringBefore("android:id=\"@+id/libraryPage\"")
        assertFalse(chat.contains("mcpServerList"))
    }

    @Test fun androidOAuthCallbackExactlyMatchesConfiguredApplicationUri() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertEquals("me.ailinux.pocket://oauth/callback", McpSecurity.APP_REDIRECT)
        assertTrue(manifest.contains("android:scheme=\"me.ailinux.pocket\""))
        assertTrue(manifest.contains("android:host=\"oauth\""))
        assertTrue(manifest.contains("android:path=\"/callback\""))
        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))
        assertTrue(manifest.contains("android:category.BROWSABLE") ||
            manifest.contains("android.intent.category.BROWSABLE"))
    }

    @Test fun cleartextOnlyAllowedForLocalhostAndBackupIsDisabled() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val network = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("@xml/network_security_config"))
        assertTrue(network.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(network.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>"))
        assertFalse(network.contains("<domain>ailinux.me</domain>"))
    }
}
