package org.jitsi.nlj.util

import org.jitsi.utils.ms
import org.jitsi.utils.stats.RateTracker
import java.time.Clock
import java.time.Duration

open class BitrateTracker @JvmOverloads constructor(
    private val windowSize: Duration,
    private val bucketSize: Duration = 1.ms,
    private val clock: Clock = Clock.systemUTC()
) {
    // Use composition to expose functions with the data types we want ([DataSize], [Bandwidth]) and not the raw types
    // that RateTracker uses.
    private val tracker = RateTracker(windowSize, bucketSize, clock)
    open fun getRate(now: Long = clock.millis()): Bandwidth = tracker.getRate(now).bps
    val rate: Bandwidth
        get() = getRate()
    fun update(dataSize: DataSize, now: Long = clock.millis()) = tracker.update(dataSize.bits, now)
    fun getAccumulatedSize(now: Long = clock.millis()) = tracker.getAccumulatedCount(now).bps
}
