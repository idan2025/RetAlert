package network.retalert.reticulum

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.retalert.domain.AckState
import network.retalert.domain.AckTracker
import network.retalert.domain.Alert
import network.retalert.domain.AnnounceEngine
import network.retalert.domain.Contacts
import network.retalert.domain.Discover
import network.retalert.domain.IfaceConfig
import network.retalert.domain.IfaceType
import network.retalert.domain.LiveTrackStore
import network.retalert.domain.RetryQueue
import network.retalert.domain.Settings
import network.retalert.domain.SettingsReceiveSettings
import network.retalert.domain.TransportIntelligence
import network.retalert.domain.normalizeHash
import network.retalert.reticulum.lxmf.LxmfRouter
import network.retalert.reticulum.meshtastic.MeshtasticInterface
import network.reticulum.Reticulum
import network.reticulum.identity.Identity
import network.reticulum.interfaces.Interface
import network.reticulum.interfaces.InterfaceAdapter
import network.reticulum.interfaces.local.LocalClientInterface
import network.reticulum.transport.Transport
import java.io.File

/** One interface as shown in the UI. */
data class InterfaceStatus(val name: String, val tier: String, val online: Boolean)

/** Observable engine state for the UI. */
data class EngineStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val deliveryHash: String = "",
    /** Attached as a client to another app's shared instance. */
    val sharedInstance: Boolean = false,
    val sharedPort: Int = 0,
    val interfaces: List<InterfaceStatus> = emptyList(),
    /** Configured interface id -> why it is not running. */
    val ifaceErrors: Map<String, String> = emptyMap(),
    val error: String = "",
)

/**
 * Owns the RNS stack, identity, LXMF router, network interfaces and the :domain
 * orchestration (AckTracker, RetryQueue, AnnounceEngine, IncomingDispatcher,
 * Discover, LiveTrackStore, MediaChannel). The Android [ReticulumService] is a
 * thin foreground-service shell around this; ViewModels call [sendAlert],
 * [reply], [ack] and the interface/announce setters directly.
 *
 * Every public entry point is safe to call from any thread, but [start] blocks
 * (disk + socket I/O) and must not run on the main thread.
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
    private val incomingWiring: IncomingWiring,
    private val rnsTransport: RnsTransport,
    private val outboxRepo: network.retalert.domain.OutboxRepository,
    private val starredRepo: network.retalert.domain.StarredRepository,
    private val settingsRepo: network.retalert.domain.SettingsRepository,
    private val contactRepo: network.retalert.domain.ContactRepository,
    private val shareInstance: ShareInstance,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val lock = Any()
    /** Background loops of the current run; cancelled on stop so a restart
     *  doesn't leave a second flusher/poller running. */
    private val loops = mutableListOf<Job>()
    @Volatile private var running = false
    @Volatile private var sharedClient = false

    /** Interfaces this engine created, keyed by config id, with the config
     *  they were built from (a changed config rebuilds the interface). */
    private val ownInterfaces = LinkedHashMap<String, Pair<IfaceConfig, Interface>>()
    private val ifaceErrors = LinkedHashMap<String, String>()
    private val ifaceStartedAt = HashMap<String, Long>()
    private val factory = InterfaceFactory(context)
    private var transportIdentityHash = ByteArray(16)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    val deliveryHashHex: String get() = lxmf.deliveryHashHex
    val isRunning: Boolean get() = running

    init {
        // Persist every per-recipient transition so the Outbox screen and a
        // restart see real delivery/ack state. Writes go through a single-lane
        // dispatcher so they land in the order the transitions happened.
        ackTracker.onStateChange = { id, r, state ->
            scope.launch(persistDispatcher) { runCatching { outboxRepo.setAckState(id, r, state) } }
        }
    }

    /** Bring up RNS, identity, interfaces, LXMF router, announce handler, retry flusher. Blocking. */
    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
        }
        _status.update { it.copy(starting = true, error = "") }
        try {
            val settings = settingsRepo.load()
            val identity = loadOrCreateIdentity()
            transportIdentityHash = identity.hash
            startReticulum(identity, settings)
            if (!sharedClient) applyInterfaces(settings)
            // Announce handler -> Discover cache (LXMF delivery only).
            Transport.registerAnnounceHandler(RetAlertAnnounceHandler(discover), "lxmf.delivery")
            starredRepo.starred().forEach { discover.star(it) }
            // LXMF router + inbound routing. The dispatcher is rebuilt per message
            // so contact / allow / deny edits apply immediately.
            lxmf.register()
            lxmf.setIncomingCallback { src, text, ts -> handleIncoming(src, text, ts) }
            lxmf.start()
            // Replay unfinished outbox alerts into the live retry queue.
            outboxRepo.unfinished().forEach { replay(it) }
            startRetryFlusher()
            startStatusPoller()
            if (!sharedClient) startInterfaceWatchdog()
            // Announce now, and again once AutoInterface has had time to find peers.
            lxmf.announce()
            loops += scope.launch { delay(ANNOUNCE_SETTLE_MS); if (running) lxmf.announce() }
            if (settings.autoAnnounce) announceEngine.setAuto(true, settings.announceInterval)
            _status.update { it.copy(running = true, starting = false, deliveryHash = lxmf.deliveryHashHex, sharedInstance = sharedClient) }
            Log.i(TAG, "engine started; lxmf=${lxmf.deliveryHashHex} shared=$sharedClient")
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            running = false
            // Tear down whatever came up so the next start() can retry cleanly
            // (Reticulum.start refuses a second start while still running).
            announceEngine.stop()
            runCatching { lxmf.stop() }
            synchronized(lock) { ownInterfaces.keys.toList().forEach { detachInterface(it) } }
            releaseMulticastLock()
            runCatching { Reticulum.stop() }
            _status.update { it.copy(running = false, starting = false, error = e.message ?: e.javaClass.simpleName) }
            throw e
        }
    }

    /** Best-effort shutdown. */
    fun stop() {
        running = false
        loops.toList().forEach { it.cancel() }
        loops.clear()
        announceEngine.stop()
        runCatching { lxmf.stop() }
        synchronized(lock) {
            ownInterfaces.keys.toList().forEach { detachInterface(it) }
            ifaceErrors.clear()
        }
        releaseMulticastLock()
        runCatching { Reticulum.stop() }
        // Reticulum.stop() detaches the shared-instance client but leaves it in
        // Transport's interface table; a later attach reuses the same name (so
        // the same interface hash) and link traffic would be routed to the dead
        // one. Drop every leftover so a restart starts from a clean table.
        runCatching { Transport.getInterfaces().forEach { Transport.deregisterInterface(it) } }
        // The old LXMF router's delivery callbacks will never fire now.
        rnsTransport.clearInFlight()
        _status.value = EngineStatus()
    }

    // -- outgoing ---------------------------------------------------------

    /** Persist + send an alert. When the engine is not up yet the alert is only
     *  persisted and goes out as soon as [start] replays the outbox. */
    fun sendAlert(alert: Alert): Alert {
        val normalized = alert.copy(
            recipients = alert.recipients.mapNotNull { normalizeHash(it) }.distinct(),
        )
        require(normalized.recipients.isNotEmpty()) { "no valid recipient hashes" }
        outboxRepo.enqueue(normalized)
        normalized.recipients.forEach { outboxRepo.setAckState(normalized.alertId, it, AckState.SENT) }
        if (running) retryQueue.enqueue(normalized)
        return normalized
    }

    /**
     * Send one live-location body to each recipient: a single opportunistic
     * packet apiece. No retries — the next fix supersedes a lost one. Recipients
     * without a path get a path request instead and are covered next tick.
     * Returns how many were handed to LXMF.
     */
    fun sendGeo(recipients: List<String>, fix: network.retalert.domain.Fix, expiresAtMs: Long? = null): Int {
        if (!running) return 0
        val legacy = runCatching { settingsRepo.load().legacyWire }.getOrDefault(false)
        val out = network.retalert.domain.Wire.geo(fix, legacy, expiresAtMs)
        var sent = 0
        for (r in recipients) {
            val h = normalizeHash(r) ?: continue
            val bytes = h.hexToByteArray()
            if (!Transport.hasPath(bytes)) {
                PathRequests.request(bytes)
                continue
            }
            lxmf.sendMessage(h, out.content, opportunistic = true, fields = out.fields)
            sent++
        }
        return sent
    }

    /** True when every online interface is LoRa-class (live shares must throttle). */
    val loraOnly: Boolean
        get() = _status.value.interfaces.filter { it.online }
            .let { up -> up.isNotEmpty() && up.all { it.tier == network.retalert.domain.Tiers.LOW } }

    /** Stop retrying an outgoing alert and delete it from the outbox. */
    fun cancelAlert(alertId: String) {
        retryQueue.remove(alertId)
        ackTracker.forget(alertId)
        outboxRepo.remove(alertId)
    }

    /** Receiver side: send an app-level ack for an inbound alert. */
    fun ack(alertId: String, sourceHex: String) {
        incomingWiring.sendAck(alertId, sourceHex)
    }

    /** Receiver side: send a reply (ack + text) for an inbound alert. */
    fun reply(alertId: String, sourceHex: String, text: String) {
        incomingWiring.sendReply(alertId, sourceHex, text)
    }

    /** Manual one-shot announce. */
    fun announceNow() = announceEngine.announceNow()

    /** Toggle auto-announce; persists and returns the effective clamped interval. */
    fun setAutoAnnounce(enabled: Boolean, interval: Double? = null): Double {
        val effective = if (running) announceEngine.setAuto(enabled, interval)
        else network.retalert.domain.clampAnnounceInterval(interval)
        val s = settingsRepo.load()
        s.autoAnnounce = enabled
        s.announceInterval = effective
        settingsRepo.save(s)
        return effective
    }

    // -- interfaces -------------------------------------------------------

    /** Re-read interface settings and add/remove interfaces to match. Changes to
     *  a shared-instance client apply on the next standalone start. */
    fun reloadInterfaces() {
        if (!running || sharedClient) return
        applyInterfaces(settingsRepo.load())
        refreshStatus()
    }

    /** RNS name the engine gives the interface built from [c]. */
    fun interfaceName(c: IfaceConfig): String = factory.rnsName(c)

    /**
     * Make the running interfaces match [settings]. With [retryOffline], also
     * rebuild interfaces that failed to start or have dropped (an RNode out of
     * Bluetooth range, a TCP server that refused us) so they come back by themselves.
     */
    private fun applyInterfaces(settings: Settings, retryOffline: Boolean = false) = synchronized(lock) {
        val wanted = settings.interfaces.filter { it.enabled }.associateBy { it.id }
        ownInterfaces.toList().forEach { (id, entry) ->
            val (cfg, iface) = entry
            val stale = wanted[id] != cfg
            val settled = System.currentTimeMillis() - (ifaceStartedAt[id] ?: 0L) > IFACE_SETTLE_MS
            val dropped = retryOffline && cfg.type in RECONNECTING && settled && !iface.online.value
            if (stale || dropped) detachInterface(id)
        }
        ifaceErrors.keys.retainAll(wanted.keys)
        for ((id, cfg) in wanted) {
            if (id in ownInterfaces) continue
            runCatching { createInterface(cfg) }
                .onSuccess {
                    ownInterfaces[id] = cfg to it
                    ifaceStartedAt[id] = System.currentTimeMillis()
                    ifaceErrors.remove(id)
                }
                .onFailure {
                    Log.w(TAG, "interface ${cfg.name} failed to start", it)
                    ifaceErrors[id] = it.message ?: it.javaClass.simpleName
                }
        }
        val needsMulticast = ownInterfaces.values.any { it.first.type == IfaceType.AUTO || it.first.type == IfaceType.UDP }
        if (needsMulticast) acquireMulticastLock() else releaseMulticastLock()
        _status.update { it.copy(ifaceErrors = ifaceErrors.toMap()) }
    }

    private fun createInterface(c: IfaceConfig): Interface {
        val iface = factory.create(c, transportIdentityHash)
        try {
            iface.start()
        } catch (e: Exception) {
            runCatching { iface.detach() }
            throw e
        }
        Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))
        Log.i(TAG, "interface up: ${iface.name}")
        return iface
    }

    private fun detachInterface(id: String) {
        val (_, iface) = ownInterfaces.remove(id) ?: return
        ifaceStartedAt.remove(id)
        runCatching { Transport.deregisterInterface(InterfaceAdapter.getOrCreate(iface)) }
        runCatching { iface.detach() }
        Log.i(TAG, "interface down: ${iface.name}")
    }

    /** Periodically retry interfaces that failed or dropped. */
    private fun startInterfaceWatchdog() {
        loops += scope.launch {
            while (running) {
                delay(IFACE_RETRY_MS)
                if (!running || sharedClient) break
                runCatching { applyInterfaces(settingsRepo.load(), retryOffline = true) }
                    .onFailure { Log.w(TAG, "interface watchdog", it) }
            }
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return
            multicastLock = wifi.createMulticastLock("retalert-autointerface").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "multicast lock unavailable", it) }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    // -- internals --------------------------------------------------------

    private fun handleIncoming(src: String, text: String, ts: Double) {
        val contacts = Contacts().apply { contactRepo.list().forEach { add(it.hash, it.name) } }
        incomingWiring.build(
            settings = SettingsReceiveSettings(settingsRepo.load()),
            contacts = contacts,
            discover = discover,
            tracks = tracks,
        ).handle(src, text, ts)
    }

    /** Restore persisted per-recipient states, then hand the alert to the retry queue
     *  (which only resends recipients still in SENT). */
    private fun replay(alert: Alert) {
        ackTracker.track(alert)
        outboxRepo.ackStates(alert.alertId).forEach { (r, state) ->
            when (state) {
                AckState.DELIVERED -> ackTracker.onDelivered(alert.alertId, r)
                AckState.ACKED -> ackTracker.onAck(alert.alertId, r)
                AckState.REPLIED -> ackTracker.onAck(alert.alertId, r, "(reply)")
                AckState.FAILED -> ackTracker.onFailed(alert.alertId, r, "failed before restart")
            }
        }
        retryQueue.enqueue(alert)
    }

    private fun startRetryFlusher() {
        loops += scope.launch {
            while (running) {
                runCatching { retryQueue.flush() }
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    private fun startStatusPoller() {
        loops += scope.launch {
            while (running) {
                refreshStatus()
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    private fun refreshStatus() {
        val ifaces = runCatching {
            transportIntelligence.classifyInterfaces().map { InterfaceStatus(it.name, it.tier, it.online) }
        }.getOrDefault(emptyList())
        // Interfaces that reconnect by themselves report why they're down.
        val runtime = synchronized(lock) {
            ownInterfaces.mapNotNull { (id, e) ->
                val m = e.second as? MeshtasticInterface ?: return@mapNotNull null
                m.lastError?.takeIf { !m.online.value }?.let { id to it }
            }.toMap() + ifaceErrors
        }
        _status.update { it.copy(interfaces = ifaces, ifaceErrors = runtime, deliveryHash = lxmf.deliveryHashHex) }
    }

    private fun loadOrCreateIdentity(): Identity {
        val file = File(context.filesDir, IDENTITY_FILE)
        Identity.fromFile(file.absolutePath)?.let { return it }
        val id = Identity.create()
        id.toFile(file.absolutePath)
        return id
    }

    private fun startReticulum(identity: Identity, settings: Settings) {
        val configDir = File(context.filesDir, RNS_CONFIG_DIR).apply { mkdirs() }
        // Attach to a host RNS instance (Columba/Sideband/MeshChat) when enabled
        // and one is listening on localhost; otherwise run our own standalone
        // stack. reticulum-kt needs both factories set before start() or the
        // attach silently fails.
        val port = settings.sharedInstancePort
        val hostRunning = settings.useSharedInstance && shareInstance.attach(port)
        Reticulum.setLocalClientFactory { port, host ->
            LocalClientInterface(name = SHARED_NAME, tcpPort = port, tcpHost = host)
        }
        Reticulum.setInterfaceRegistrar { iface ->
            if (iface is Interface) Transport.registerInterface(InterfaceAdapter.getOrCreate(iface))
        }
        val rns = Reticulum.start(
            configDir = configDir.absolutePath,
            enableTransport = false,
            shareInstance = false, // client-attach only; we never host for other apps
            sharedInstancePort = port,
            connectToSharedInstance = hostRunning,
            transportIdentity = identity,
        )
        sharedClient = rns.isConnectedToSharedInstance
        _status.update { it.copy(sharedPort = port) }
        Log.i(TAG, "startReticulum: hostRunning=$hostRunning port=$port shared=$sharedClient")
    }

    companion object {
        private const val TAG = "RetAlert/Engine"
        private const val IDENTITY_FILE = "retalert_identity"
        private const val RNS_CONFIG_DIR = "reticulum"
        private const val FLUSH_INTERVAL_MS = 1000L
        private const val STATUS_INTERVAL_MS = 3000L
        private const val ANNOUNCE_SETTLE_MS = 15_000L
        private const val IFACE_RETRY_MS = 30_000L
        /** RNode detect + radio init takes a few seconds before it reports online. */
        private const val IFACE_SETTLE_MS = 20_000L
        /** Types whose link can drop and must be rebuilt (TCP clients reconnect by themselves). */
        private val RECONNECTING = setOf(IfaceType.RNODE, IfaceType.I2P)
        const val AUTO_NAME = "AutoInterface"
        const val TCP_PREFIX = "TCP "
        const val SHARED_NAME = "SharedInstance"
    }
}
