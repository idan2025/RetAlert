package network.retalert.app.platform

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.retalert.domain.Fix
import network.retalert.domain.FixSource
import network.retalert.domain.RealClock
import network.retalert.domain.liveShareInterval
import network.retalert.reticulum.ReticulumEngine
import network.retalert.reticulum.ReticulumService
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows about the running live share. */
data class ShareState(
    val active: Boolean = false,
    val recipients: List<String> = emptyList(),
    val reason: String = "",
    val intervalS: Double = 0.0,
    /** Epoch seconds when the share stops by itself. */
    val untilEpoch: Double = 0.0,
    val lastFix: Fix? = null,
    val lastSentEpoch: Double = 0.0,
    val updatesSent: Int = 0,
    /** Why nothing is being sent right now (e.g. permission off); empty when fine. */
    val problem: String = "",
)

/**
 * Real-time location sharing: sends a `geo:` fix to the recipients every
 * interval until stopped or the duration runs out. Started by an emergency
 * alert whose preset asks for live GPS (or panic, per settings), or manually
 * from the Map. App-scoped, so it keeps going after the Map screen closes;
 * while active the mesh service carries the `location` foreground type so
 * fixes keep flowing with the screen off.
 */
@Singleton
class LocationSharer @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val engine: ReticulumEngine,
    private val fixSource: FixSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Bumped on every start/stop; a loop only ends the share it owns. */
    private var generation = 0L

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    /**
     * Start (or replace) a live share. [intervalS] is the preferred cadence;
     * over LoRa-only links it is stretched to [loraThrottleS]. Recipients are
     * merged when a share is already running, and the longer deadline wins.
     */
    @Synchronized
    fun start(
        recipients: List<String>,
        minutes: Int,
        intervalS: Double,
        reason: String,
        loraThrottleS: Double? = null,
    ) {
        if (recipients.isEmpty()) return
        val now = RealClock.nowEpoch()
        val prev = _state.value.takeIf { it.active }
        val merged = ((prev?.recipients ?: emptyList()) + recipients).distinct()
        val until = maxOf(prev?.untilEpoch ?: 0.0, now + minutes.coerceAtLeast(1) * 60.0)
        launch(merged, until, intervalS, reason, loraThrottleS, prev)
    }

    /**
     * Resume a share that was running when the process died (Android killed
     * it, or the app restarted). The sticky mesh service brings the process
     * back; an emergency share must not silently stop.
     */
    @Synchronized
    fun resumeIfActive() {
        if (_state.value.active) return
        val p = prefs
        val until = p.getLong(K_UNTIL, 0L).toDouble()
        val recipients = p.getStringSet(K_RECIPIENTS, emptySet()).orEmpty().toList()
        if (recipients.isEmpty() || until <= RealClock.nowEpoch()) { clearSaved(); return }
        val throttle = p.getFloat(K_THROTTLE, -1f).takeIf { it > 0 }?.toDouble()
        launch(recipients, until, p.getFloat(K_INTERVAL, 15f).toDouble(), p.getString(K_REASON, "resumed").orEmpty(), throttle, null)
        Log.i(TAG, "resumed live share after restart")
    }

    private fun launch(
        merged: List<String>,
        until: Double,
        intervalS: Double,
        reason: String,
        loraThrottleS: Double?,
        prev: ShareState?,
    ) {
        prefs.edit()
            .putStringSet(K_RECIPIENTS, merged.toSet())
            .putLong(K_UNTIL, until.toLong())
            .putFloat(K_INTERVAL, intervalS.toFloat())
            .putFloat(K_THROTTLE, (loraThrottleS ?: -1.0).toFloat())
            .putString(K_REASON, reason)
            .apply()
        _state.value = ShareState(
            active = true, recipients = merged, reason = reason, intervalS = intervalS,
            untilEpoch = until, lastFix = prev?.lastFix, lastSentEpoch = prev?.lastSentEpoch ?: 0.0,
            updatesSent = prev?.updatesSent ?: 0,
        )
        setServiceLocationMode(true)
        job?.cancel()
        val gen = ++generation
        job = scope.launch {
            while (isActive) {
                val s = _state.value
                if (!s.active || RealClock.nowEpoch() >= s.untilEpoch) break
                tick(s)
                val wait = liveShareInterval(s.intervalS, engine.loraOnly, loraThrottleS)
                delay((wait * 1000).toLong())
            }
            // Only a share that ran out ends itself; a cancelled (replaced or
            // stopped) loop must not tear down its successor.
            if (isActive) finish(gen)
        }
        Log.i(TAG, "live share started: ${merged.size} recipient(s), until=$until ($reason)")
    }

    /** Stop sharing now. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        finish(++generation)
    }

    /** Send a single fix now (e.g. the one-shot location that follows an alert). */
    fun sendOnce(recipients: List<String>) {
        if (recipients.isEmpty()) return
        scope.launch {
            val fix = runCatching { fixSource.getFix() }.getOrNull() ?: return@launch
            engine.sendGeo(recipients, fix)
            _state.update { it.copy(lastFix = fix) }
        }
    }

    private fun tick(s: ShareState) {
        if (!hasLocationPermission()) {
            _state.update { it.copy(problem = "Location permission is off — open RetAlert to allow it") }
            return
        }
        val fix = runCatching { fixSource.getFix() }.getOrNull()
        if (fix == null) {
            _state.update { it.copy(problem = "Waiting for a GPS fix…") }
            Log.w(TAG, "no location fix this tick")
            return
        }
        val n = engine.sendGeo(s.recipients, fix, expiresAtMs = (s.untilEpoch * 1000).toLong())
        _state.update {
            it.copy(
                lastFix = fix, lastSentEpoch = RealClock.nowEpoch(),
                updatesSent = it.updatesSent + if (n > 0) 1 else 0,
                problem = if (n > 0) "" else "No route to recipients yet — retrying",
            )
        }
    }

    @Synchronized
    private fun finish(gen: Long) {
        if (gen != generation) return
        clearSaved()
        if (!_state.value.active) return
        _state.update { it.copy(active = false) }
        setServiceLocationMode(false)
        Log.i(TAG, "live share stopped")
    }

    private fun hasLocationPermission(): Boolean =
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private val prefs by lazy { ctx.getSharedPreferences("live_share", Context.MODE_PRIVATE) }

    private fun clearSaved() = prefs.edit().clear().apply()

    private fun setServiceLocationMode(enabled: Boolean) {
        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, ReticulumService::class.java)
                    .setAction(ReticulumService.ACTION_LOCATION)
                    .putExtra(ReticulumService.EXTRA_ENABLED, enabled),
            )
        }.onFailure { Log.w(TAG, "could not switch service location mode: ${it.message}") }
    }

    private companion object {
        const val TAG = "RetAlert/Share"
        const val K_RECIPIENTS = "recipients"
        const val K_UNTIL = "until"
        const val K_INTERVAL = "interval"
        const val K_THROTTLE = "lora_throttle"
        const val K_REASON = "reason"
    }
}
