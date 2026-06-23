package network.retalert.domain

/** An ordered sequence of key tokens that fires a trigger. Mirrors KeyCombo. */
data class KeyCombo(
    val combo: List<String>,
    val trigger: String,
    val arm: Boolean = false,   // this combo arms (does not fire) the engine
)

/** Maps detected key combos to preset triggers with arm/dedup logic. Mirrors
 *  HardwareKeyManager. Persistence of combos → :data (Room). */
class HardwareKeyManager(
    private val fireFn: (String) -> Any?,
    private val armWindowS: Double = DEFAULT_ARM_WINDOW_S,
    private val cooldownS: Double = DEFAULT_COOLDOWN_S,
    private val clock: Clock = RealClock,
) {
    private val combos = ArrayList<KeyCombo>()
    private val buffer = ArrayList<String>()
    private val maxBuffer = 8
    private var armed = false
    private var armedAt = 0.0
    private val lastFire = LinkedHashMap<List<String>, Double>()

    val isArmed: Boolean get() = armed

    /** Register a combo -> trigger (replaces any existing combo with the same seq). */
    fun register(combo: List<String>, trigger: String, arm: Boolean = false) {
        require(combo.isNotEmpty()) { "combo must be non-empty" }
        combos.removeAll { it.combo == combo }
        combos.add(KeyCombo(combo, trigger, arm))
    }

    fun register(vararg combo: String, trigger: String, arm: Boolean = false) =
        register(combo.toList(), trigger, arm)

    fun unregister(combo: List<String>): Boolean {
        val before = combos.size
        combos.removeAll { it.combo == combo }
        return combos.size < before
    }

    fun listCombos(): List<KeyCombo> = combos.toList()

    fun clear() { combos.clear(); buffer.clear(); armed = false; lastFire.clear() }

    fun arm() { armed = true; armedAt = clock.monotonic() }
    fun disarm() { armed = false }

    private fun armExpired(): Boolean {
        if (!armed) return true
        return (clock.monotonic() - armedAt) > armWindowS
    }

    /** Feed one observed key press. Returns the trigger fired, or null. */
    fun feed(key: String): String? {
        buffer.add(key)
        if (buffer.size > maxBuffer) {
            val keep = buffer.takeLast(maxBuffer)
            buffer.clear(); buffer.addAll(keep)
        }
        return check()
    }

    /** Feed several keys at once. Returns the last trigger fired, if any. */
    fun feedSequence(keys: List<String>): String? {
        var result: String? = null
        for (k in keys) { feed(k)?.let { result = it } }
        return result
    }

    private fun check(): String? {
        val now = clock.monotonic()
        for (kc in combos) {
            val n = kc.combo.size
            if (buffer.size < n) continue
            if (buffer.takeLast(n) != kc.combo) continue
            // Matched. Arm combo: arm the engine, don't fire.
            if (kc.arm) {
                if (armed && !armExpired()) continue   // already armed; let longer fire combo assemble
                arm(); buffer.clear(); return null
            }
            // Fire combo: only if armed (and not expired), or no arm combos at all (direct-fire).
            val armRequired = combos.any { it.arm }
            if (armRequired && (!armed || armExpired())) { buffer.clear(); return null }
            // Cooldown dedup.
            val last = lastFire[kc.combo] ?: 0.0
            if (now - last < cooldownS) { buffer.clear(); return null }
            lastFire[kc.combo] = now
            buffer.clear()
            disarm()   // a fire consumes the arm
            fireFn(kc.trigger)
            return kc.trigger
        }
        return null
    }

    companion object {
        const val DEFAULT_ARM_WINDOW_S = 5.0
        const val DEFAULT_COOLDOWN_S = 5.0
    }
}