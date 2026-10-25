package me.ailinux.pocket

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DownloadVpnConstraintTest {
    private val bg = File("src/main/java/me/ailinux/pocket/ModelBackgroundDownloads.kt").readText()
    private val ui = File("src/main/java/me/ailinux/pocket/MainActivity.kt").readText()

    @Test fun generalUidtNetworkRequirementAllowsVpnOrPrivateDnsTransport() {
        // A NetworkRequest.Builder() requires NET_CAPABILITY_NOT_VPN by
        // default on Android; an explicit INTERNET-only download must not.
        assertTrue(bg.contains("removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)"))
        assertTrue(bg.contains("addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)"))
        assertTrue(bg.contains("if (wifiOnly) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)"))
    }

    @Test fun processDiagnosticsExposeOnlyTransportFlagsNeverSsidOrIp() {
        assertTrue(bg.contains("hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)"))
        assertTrue(bg.contains("hasTransport(NetworkCapabilities.TRANSPORT_VPN)"))
        assertTrue(bg.contains("getPendingJobReasons(entry.id)"))
        assertTrue(ui.contains("Netzwerktransport"))
        assertFalse(bg.contains("getConnectionInfo().ssid"))
    }

    @Test fun pendingNetworkWithValidatedVpnIsDiagnosedAsConstraintMismatch() {
        val status = DownloadSchedulerState.describe(
            "Qwen3_1.7B.litertlm", "queued", false, true,
            setOf(DownloadSchedulerState.Reason.NETWORK),
            90_000L, 0L, 2_056_729_520L, "",
            DownloadSchedulerState.NetworkInfo(
                validated = true, hasInternet = true,
                wifi = true, vpn = true, metered = false
            )
        )
        assertTrue(status.label.contains("trotz aktiver Internetverbindung"))
        assertTrue(status.waitingForNetwork)
        assertFalse(status.canResume)
    }

    @Test fun offlineStateStillReportsWaitingForNetwork() {
        val status = DownloadSchedulerState.describe(
            "Qwen3_1.7B.litertlm", "queued", false, true,
            setOf(DownloadSchedulerState.Reason.NETWORK),
            90_000L, 0L, 2_056_729_520L, "",
            DownloadSchedulerState.NetworkInfo(
                validated = false, hasInternet = false,
                wifi = false, vpn = false, metered = false
            )
        )
        assertTrue(status.label.contains("Wartet auf Netzwerk"))
    }
}
