package network.retalert.domain

import java.util.UUID

/** Time abstraction so domain logic is unit-testable without the wall clock. */
interface Clock {
    /** Wall-clock epoch seconds (Python `time.time()` analogue). */
    fun nowEpoch(): Double
    /** Monotonic seconds (Python `time.monotonic()` analogue). */
    fun monotonic(): Double
}

object RealClock : Clock {
    private val bootNanos = System.nanoTime()
    override fun nowEpoch(): Double = System.currentTimeMillis() / 1000.0
    override fun monotonic(): Double = (System.nanoTime() - bootNanos) / 1e9
}

/** A mutable clock for tests; advance via `tickTo` / `advance`. */
class FakeClock(
    var epoch: Double = 1_700_000_000.0,
    var mono: Double = 0.0,
) : Clock {
    override fun nowEpoch(): Double = epoch
    override fun monotonic(): Double = mono
    fun advanceEpoch(seconds: Double) { epoch += seconds }
    fun advanceMono(seconds: Double) { mono += seconds }
}

fun randomHex(byteLength: Int): String =
    UUID.randomUUID().toString().replace("-", "").let { it.take(byteLength * 2).padEnd(byteLength * 2, '0') }