package network.retalert.app.platform.keys

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import network.retalert.domain.HardwareKeyManager
import network.retalert.domain.KeyComboRepository
import javax.inject.Inject

/**
 * Captures volume-key sequences and feeds them to [HardwareKeyManager], which
 * resolves + enqueues a preset alert when a registered combo fires. The service
 * loads persisted combos from [KeyComboRepository] at connect time and registers
 * them with the manager.
 *
 * Key consumption policy: while the manager is armed (an arm-combo was matched
 * and the arm window has not expired) we consume both volume keys so the user's
 * subsequent presses assemble the fire-combo without the system ratcheting the
 * media volume. When disarmed we pass the keys through (`false`) so normal
 * volume control still works. A fire-combo match is always consumed.
 *
 * Combo refresh: accessibility services cannot be programmatically
 * (re)started/updated from app code. The service reloads combos from
 * [KeyComboRepository] in [onServiceConnected]; after registering/editing
 * combos in Settings the user must toggle the service off/on in system
 * Accessibility settings to pick up the new set. (We can't even rebind the
 * service from within itself.)
 */
@AndroidEntryPoint
class HardwareKeyAccessibilityService : AccessibilityService() {

    @Inject lateinit var hardwareKeyManager: HardwareKeyManager
    @Inject lateinit var keyCombos: KeyComboRepository

    /** Key-only service: no accessibility events to act on. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    /** Required override; nothing to interrupt. */
    override fun onInterrupt() { /* no-op */ }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0
        }
        // Load persisted combos and register them. The manager is a singleton,
        // so a fresh service connect re-seeds it from the latest saved set.
        hardwareKeyManager.clear()
        keyCombos.list().forEach { kc ->
            hardwareKeyManager.register(kc.combo, kc.trigger, kc.arm)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val token = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "volume_up"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "volume_down"
            else -> return false
        }
        // Feed the key; a non-null return means a fire-combo just fired.
        val fired = hardwareKeyManager.feed(token)
        if (fired != null) return true
        // While armed, consume volume keys so the fire-combo can be assembled
        // without the system adjusting media volume. Disarmed: pass through.
        return hardwareKeyManager.isArmed
    }
}