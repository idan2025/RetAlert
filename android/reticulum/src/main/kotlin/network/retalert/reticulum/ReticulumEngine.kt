package network.retalert.reticulum

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.retalert.domain.AckTracker
import network.retalert.domain.AnnounceEngine
import network.retalert.domain.Contacts
import network.retalert.domain.Discover
import network.retalert.domain.LiveTrackStore
import network.retalert.domain.RetryQueue
import network.retalert.domain.SettingsReceiveSettings
import network.retalert.domain.TransportIntelligence
import network.retalert.reticulum.lxmf.LxmfRouter
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.transport.Transport
import java.io.File

/**
 * Owns the RNS stack, identity, LXMF router, and the :domain orchestration
 * (AckTracker, RetryQueue, AnnounceEngine, IncomingDispatcher, Discover,
 * LiveTrackStore, MediaChannel). The Android [ReticulumService] is a thin
 * foreground-service shell around this; :app / ViewModels talk to it via the
 * repository + notifier seams.
 *
 * Parity with `retalert/daemon.py` `EmergencyDaemon`.
 */
class ReticulumEngine(
    private val context: Context,
    private val ackTracker: AckTracker,
    private val retryQueue: RetryQueue,
    private val discover: Discover,
    private val tracks: LiveTrackStore,
    val transportIntelligence: TransportIntelligence,
    private val lxmf: LxmfRouter,
    private val announceEngine: AnnounceEngine,
    val mediaChannel: network.retalert.domain.MediaChannel,
    private val rnsTransport: RnsTransport,
    private val incomingWiring: IncomingWiring,
    private val notifier: IncomingNotifier,
    private val outboxRepo: network.retalert.domain.OutboxRepository,
    private val starredRepo: network.retalert.domain.StarredRepository,
    private val settingsRepo: network.retalert.domain.SettingsRepository,
    private val contactRepo: network.retalert.domain.ContactRepository,
    private val shareInstance: ShareInstance,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var dispatcher: network.retalert.domain.IncomingDispatcher? = null
    @Volatile private var running = false

    val deliveryHashHex: String get() = lxmf.deliveryHashHex
    val isRunning: Boolean get() = running

    /** Bring up RNS, identity, LXMF router, announce handler, retry flusher. */
    fun start() {
        if (running) return
        running = true
        try {
            val identity = loadOrCreateIdentity()
            startReticulum(identity)
            // Announce handler -> Discover cache (LXMF delivery only).
            Transport.registerAnnounceHandler(RetAlertAnnounceHandler(discover), "lxmf.delivery")
            // LXMF router + inbound routing.
            lxmf.register()
            lxmf.setIncomingCallback { src, text, ts -> dispatcher?.handle(src, text, ts) }
            lxmf.start()
            lxmf.announce()
            // Wire incoming dispatcher with live settings/contacts + persisted starred.
            val contacts = Contacts().apply {
                contactRepo.list().forEach { add(it.hash, it.name) }
            }
            val settings = settingsRepo.load()
            starredRepo.starred().forEach { discover.star(it) }
            dispatcher = incomingWiring.build(
                settings = SettingsReceiveSettings(settings),
                contacts = contacts,
                discover = discover,
                tracks = tracks,
            )
            // Replay pending outbox into the live retry queue.
            outboxRepo.pending().forEach { retryQueue.enqueue(it) }
            startRetryFlusher()
            Log.i(TAG, "engine started; lxmf=${lxmf.deliveryHashHex}")
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            running = false
            throw e
        }
    }

    /** Best-effort shutdown. */
    fun stop() {
        running = false
        announceEngine.stop()
        runCatching { lxmf.stop() }
        runCatching { Reticulum.stop() }
        scope.cancel()
    }

    /** Send an alert (enqueue + immediate send via delivery plan). */
    fun sendAlert(alert: network.retalert.domain.Alert): network.retalert.domain.Alert {
        outboxRepo.enqueue(alert)
        retryQueue.enqueue(alert)
        return alert
    }

    /** Manual one-shot announce. */
    fun announceNow() = announceEngine.announceNow()

    /** Toggle auto-announce; returns the effective clamped interval. */
    fun setAutoAnnounce(enabled: Boolean, interval: Double? = null): Double =
        announceEngine.setAuto(enabled, interval)

    private fun startRetryFlusher() {
        scope.launch {
            while (running) {
                runCatching { retryQueue.flush() }
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    private fun loadOrCreateIdentity(): Identity {
        val file = File(context.filesDir, IDENTITY_FILE)
        Identity.fromFile(file.absolutePath)?.let { return it }
        val id = Identity.create()
        id.toFile(file.absolutePath)
        return id
    }

    private fun startReticulum(identity: Identity) {
        val configDir = File(context.filesDir, RNS_CONFIG_DIR).apply { mkdirs() }
        // Attach to a host RNS instance (Sideband/Columba/MeshChat) when one is
        // running locally; otherwise start our own standalone stack. The common
        // case (no external host) boots exactly as before.
        val hostRunning = runCatching { shareInstance.attach() }.getOrDefault(false)
        val connectToShared = hostRunning
        val shareInstanceMode = false // standalone server off; first release is client-attach only
        Log.i(TAG, "startReticulum: hostRunning=$hostRunning connectToShared=$connectToShared")
        Reticulum.start(
            configDir.absolutePath,
            /* enableTransport             */ false,
            /* shareInstance                */ shareInstanceMode,
            /* sharedInstancePort           */ Reticulum.DEFAULT_SHARED_INSTANCE_PORT,
            /* connectToSharedInstance      */ connectToShared,
            /* transportIdentityOverride    */ identity,
            /* respondToProbes              */ false,
            /* useImplicitProof             */ false,
            /* enableRemoteManagement       */ false,
            /* remoteManagementAllowedHashes */ emptyList(),
            /* panicOnInterfaceError        */ false,
            /* blackholeSourceHashes        */ emptyList(),
            /* interfaceDiscoverySources   */ emptyList(),
            /* rpcKeyHex                    */ "",
        )
    }

    private companion object {
        const val TAG = "RetAlert/Engine"
        const val IDENTITY_FILE = "retalert_identity"
        const val RNS_CONFIG_DIR = "reticulum"
        const val FLUSH_INTERVAL_MS = 1000L
    }
}