package me.ailinux.pocket.mcp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Regression: RC11 exposed the native model agent only inside CHAT -> MCP.
 * Users configuring server OAuth under SETUP could not find the AUTO-BRIDGE
 * menu, and might erase App Data thinking it was an installation problem.
 */
class McpSettingsDiscoverabilityTest {
    private val ui = File("src/main/res/layout/activity_main.xml").readText()
    private val activity = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()

    @Test fun setupShowsModelBridgeAndStatusNextToMcpProfiles() {
        val section = ui.substringAfter("MCP VERBINDUNGEN")
            .substringBefore("android:id=\"@+id/appVersion\"")
        assertTrue(section.contains("MCP MODELL-AGENT · AUTO-BRIDGE"))
        assertTrue(section.contains("android:id=\"@+id/mcpAgentSettingsStatus\""))
        assertTrue(section.contains("android:id=\"@+id/mcpAgentSettingsButton\""))
        assertTrue(section.contains("android:id=\"@+id/mcpToolListSettingsButton\""))
        assertTrue(section.contains("android:id=\"@+id/mcpServerInstructionsButton\""))
        assertTrue(section.contains("android:id=\"@+id/addMcpServerButton\""))
        assertTrue(section.contains("android:id=\"@+id/mcpServerList\""))
        assertTrue(section.indexOf("@+id/mcpAgentSettingsButton") <
            section.indexOf("@+id/addMcpServerButton"))
    }

    @Test fun settingsAndChatShareExactlyOneAgentMenuAndApprovalImplementation() {
        val chat = activity.substringAfter(
            "findViewById<MaterialButton>(R.id.chatMcpButton).setOnClickListener")
            .substringBefore("findViewById<MaterialButton>(R.id.importButton)")
        assertTrue(chat.contains("showMcpAgentMenu()"))
        assertTrue(chat.contains("R.id.mcpAgentSettingsButton"))
        assertTrue(chat.contains("R.id.mcpToolListSettingsButton"))
        assertTrue(chat.contains("R.id.mcpServerInstructionsButton"))
        assertTrue(chat.contains("mcpUi.showToolsChooser()"))
        assertTrue(chat.contains("mcpUi.showServerGuidanceChooser()"))
        assertEquals(1, Regex("private fun showMcpAgentMenu\\(").findAll(activity).count())
        assertTrue(activity.contains("mcpAgent.showMenu("))
    }

    @Test fun userCanPrepareBridgeBeforeModelDownloadFinishes() {
        assertTrue(activity.contains("if (activeModel != null) rebindNativeMcpTools(active)"))
        assertTrue(activity.contains("val prepared = active != null && activeModel == null"))
        assertTrue(activity.contains("●  MCP VORBEREITET"))
        assertTrue(activity.contains("MCP-BRIDGE VORBEREITET"))
        assertTrue(activity.contains("GGUF unter MODELLE laden"))
        val modelLoad = activity.substringAfter("activeModel = file")
        assertTrue(modelLoad.contains("updateMcpBadge(mcpAgent.current())"))
        // Loading a GGUF with an opt-in selected bridge must still bind
        // only the three small local meta functions, not entire inventories.
        assertTrue(activity.contains("val activeMcp = mcpAgent.current()"))
        assertTrue(activity.contains("McpAgentBridge.nativeToolsJson(it.tools)"))
    }

    @Test fun screenshotRegressionAgentAndAutoServerDialogActuallyShowOptions() {
        val agent = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        val menu = agent.substringAfter("fun showMenu(")
            .substringBefore("private fun chooseAutoServers()")
        val auto = agent.substringAfter("private fun chooseAutoServers()")
            .substringBefore("private fun chooseServer()")
        // A message hides the list in Android AppCompat's AlertDialog.
        // A custom header is allowed; the list must own the content panel.
        assertTrue(menu.contains(".setCustomTitle(listDialogHeader("))
        assertTrue(menu.contains(".setItems(options)"))
        assertFalse(menu.contains(".setMessage("))
        assertTrue(auto.contains(".setCustomTitle(listDialogHeader("))
        assertTrue(auto.contains(".setMultiChoiceItems("))
        // The no-profile error legitimately uses setMessage before returning.
        val dialog = auto.substringAfter("val checked = BooleanArray")
        assertFalse(dialog.contains(".setMessage("))
        assertTrue(menu.contains("AUTO-BRIDGE · Tools automatisch finden (RC11)"))
    }

    @Test fun remainingQuickActionAndSmallModelPickersAreVisible() {
        val quick = activity.substringAfter("private fun showQuickActionPicker()")
            .substringBefore("private fun showQuickMcpAction(")
        assertTrue(quick.contains(".setItems(actions.map"))
        assertFalse(quick.contains(".setMessage("))
        // RC1 now fetches small-model suggestions from the live HF catalogue.
        val starter = activity.substringAfter("private fun quickStart()")
            .substringBefore("private fun promptDirectUrl()")
        assertTrue(starter.contains("HuggingFaceDiscovery.recommended()"))
        assertTrue(starter.contains("showHuggingFaceResults(models"))
        val results = activity.substringAfter("private fun showHuggingFaceResults(")
            .substringBefore("private fun quickStart()")
        assertTrue(results.contains(".setItems(descriptions)"))
        assertFalse(results.contains(".setMessage("))
    }

    @Test fun noAutoNetworkCallOrPermissionsGrantedJustByOpeningSetup() {
        val onCreate = activity.substringAfter("override fun onCreate(")
            .substringBefore("override fun onSaveInstanceState(")
        assertTrue(onCreate.contains("R.id.mcpAgentSettingsButton"))
        assertFalse(onCreate.contains("McpConnection.callTool("))
        assertTrue(activity.contains("showMcpAgentMenu()"))
        val agent = File("src/main/java/me/ailinux/pocket/mcp/McpAgentUi.kt").readText()
        assertTrue(agent.contains("requestAutoApproval"))
        assertTrue(agent.contains("McpConnection.callTool(authorized, current, plan.arguments)"))
        assertTrue(agent.contains("AUTO-BRIDGE · Tools automatisch finden (RC11)"))
    }
}
