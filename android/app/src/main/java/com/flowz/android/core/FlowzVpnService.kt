package com.flowz.android.core

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

/**
 * Skeleton VpnService: owns the Android TUN fd and the libbox core.
 * It implements both libbox interfaces directly (SFA splits them into
 * BoxService / VPNService / PlatformInterfaceWrapper; the skeleton merges
 * them to keep the first build small).
 *
 * Known skeleton limitations, fixed in v1:
 * - No foreground notification: the system may reclaim the service in
 *   the background. Reconnect then.
 * - No real interface monitor / neighbor monitor / per-app routing.
 * - localDNSTransport() returns null: the demo config uses remote DNS
 *   only, so the platform transport is never instantiated.
 */
class FlowzVpnService : VpnService(), CommandServerHandler, PlatformInterface {

    companion object {
        const val ACTION_START = "com.flowz.android.START"
        const val ACTION_STOP = "com.flowz.android.STOP"
        private const val TAG = "FlowzVpn"
    }

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var setupDone = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCore()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startCore()
        }
        return START_STICKY
    }

    private fun startCore() {
        CoreState.setStatus(HubState.CONNECTING)
        try {
            if (!setupDone) {
                Libbox.setup(
                    SetupOptions().apply {
                        basePath = filesDir.absolutePath
                        workingPath = filesDir.resolve("box").absolutePath
                        tempPath = cacheDir.resolve("box").absolutePath
                        fixAndroidStack = true
                        appVersion = "0.1"
                        appMarketingVersion = "0.1"
                    }
                )
                setupDone = true
            }
            Libbox.promoteOOMDraft()
            Libbox.discardPowerReportDraft()
            val server = CommandServer(this, this)
            server.start()
            commandServer = server
            server.startOrReloadService(ConfigBuilder.demoConfig(), OverrideOptions())
            CoreState.setStatus(HubState.CONNECTED)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            CoreState.setStatus(HubState.FAILED, e.message ?: "启动失败")
            stopCore()
        }
    }

    private fun stopCore() {
        try {
            commandServer?.closeService()
        } catch (_: Exception) {
        }
        try {
            commandServer?.close()
        } catch (_: Exception) {
        }
        commandServer = null
        try {
            tunFd?.close()
        } catch (_: Exception) {
        }
        tunFd = null
        CoreState.setStatus(HubState.IDLE)
    }

    override fun onDestroy() {
        stopCore()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopCore()
        super.onRevoke()
    }

    // ---- CommandServerHandler ----

    override fun serviceStop() {
        stopCore()
    }

    override fun serviceReload() {
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? = null

    override fun setSystemProxyEnabled(enabled: Boolean) {
    }

    override fun triggerNativeCrash() {
        throw UnsupportedOperationException("unsupported")
    }

    override fun writeDebugMessage(message: String?) {
        if (message != null) {
            Log.d(TAG, message)
        }
    }

    override fun connectSSHAgent(): Int = -1

    // ---- PlatformInterface ----

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        val builder = Builder()
        builder.setSession("FlowZ")
        builder.setMtu(options.getMTU())
        val inet4 = options.getInet4Address()
        while (inet4.hasNext()) {
            val p = inet4.next() ?: continue
            builder.addAddress(p.address(), p.prefix())
        }
        val inet6 = options.getInet6Address()
        while (inet6.hasNext()) {
            val p = inet6.next() ?: continue
            builder.addAddress(p.address(), p.prefix())
        }
        val dns = options.getDNSServerAddress()
        var hasDns = false
        while (dns.hasNext()) {
            val s = dns.next() ?: continue
            builder.addDnsServer(s)
            hasDns = true
        }
        if (!hasDns) {
            // Skeleton fallback: the demo config gives TUN no DNS server,
            // so hand the system one to keep names resolving in the tunnel.
            builder.addDnsServer("223.5.5.5")
        }
        if (options.getAutoRoute()) {
            val r4 = options.getInet4RouteAddress()
            while (r4.hasNext()) {
                val p = r4.next() ?: continue
                builder.addRoute(p.address(), p.prefix())
            }
            val r6 = options.getInet6RouteAddress()
            while (r6.hasNext()) {
                val p = r6.next() ?: continue
                builder.addRoute(p.address(), p.prefix())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
        builder.setBlocking(false)
        val pfd = builder.establish() ?: throw IllegalStateException("VPN 建立失败")
        tunFd = pfd
        // libbox dup()s this fd on the Go side.
        return pfd.detachFd()
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner? = null

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
    }

    override fun getInterfaces(): NetworkInterfaceIterator =
        object : NetworkInterfaceIterator {
            override fun next(): NetworkInterface? = null
            override fun hasNext(): Boolean = false
        }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
    }

    override fun sendNotification(notification: Notification) {
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener) {
    }

    override fun registerMyInterface(name: String) {
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String,
        environ: StringIterator,
        term: String,
        rows: Int,
        cols: Int
    ): ShellSession? = null

    override fun lookupUser(username: String): PlatformUser? = null

    override fun lookupSFTPServer(): String = ""

    override fun readSystemSSHHostKey(): String = ""

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession? = null

    override fun usePlatformAutoRedirect(): Boolean = false

    override fun createAutoRedirect(
        options: ByteArray,
        handler: AutoRedirectHandler
    ): AutoRedirectSession? = null
}
