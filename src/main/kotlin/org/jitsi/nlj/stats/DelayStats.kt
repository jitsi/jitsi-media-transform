/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.nlj.stats

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.utils.increaseAndGet

open class DelayStats(
    thresholdsNoMax: LongArray = longArrayOf(2, 5, 20, 50, 200, 500, 100)
) {
    private val totalDelayMs = LongAdder()
    private val totalCount = LongAdder()
    private val averageDelayMs: Double
        get() = totalDelayMs.sum() / totalCount.sum().toDouble()
    private val maxDelayMs = AtomicLong(0)

    private val thresholds = longArrayOf(*thresholdsNoMax, Long.MAX_VALUE)
    private val thresholdCounts = Array(thresholds.size + 1) { LongAdder() }

    fun addDelay(delayMs: Long) {
        if (delayMs >= 0) {
            totalDelayMs.add(delayMs)
            maxDelayMs.increaseAndGet(delayMs)
            totalCount.increment()

            findBucket(delayMs).increment()
        }
    }

    private fun findBucket(delayMs: Long): LongAdder {
        // The vast majority of values are in the first bucket, so linear search is likely faster than binary.
        for (i in thresholds.indices) {
            if (delayMs <= thresholds[i]) return thresholdCounts[i]
        }
        return thresholdCounts.last()
    }

    fun toJson() = OrderedJsonObject().apply {
        val snapshot = getSnapshot()
        put("average_delay_ms", snapshot.averageDelayMs)
        put("max_delay_ms", snapshot.maxDelayMs)
        put("total_count", snapshot.totalCount)

        val buckets = OrderedJsonObject().apply {
            for (i in 0..snapshot.buckets.size - 2) {
                put("<= ${snapshot.buckets[i].first} ms", snapshot.buckets[i].second)
            }
            val indexOfSecondToLast = snapshot.buckets.size - 2
            put("> ${snapshot.buckets[indexOfSecondToLast].first} ms", snapshot.buckets.last().second)
        }
        put("buckets", buckets)
        put("p99<=", snapshot.p99bound)
        put("p999<=", snapshot.p999bound)
    }

    fun getSnapshot(): Snapshot {

        val buckets = Array(thresholds.size) { i -> Pair(thresholds[i], thresholdCounts[i].sum()) }
        val totalCount = totalCount.sum()

        var p99 = Long.MAX_VALUE
        var p999 = Long.MAX_VALUE
        var sum: Long = 0
        buckets.forEach {
            sum += it.second
            if (it.first < p99 && sum > 0.99 * totalCount) p99 = it.first
            if (it.first < p999 && sum > 0.999 * totalCount) p999 = it.first
        }

        // Not enough data
        if (totalCount < 100 || p99 == Long.MAX_VALUE) p99 = -1
        if (totalCount < 1000 || p999 == Long.MAX_VALUE) p999 = -1

        return Snapshot(
            averageDelayMs = averageDelayMs,
            maxDelayMs = maxDelayMs.get(),
            totalCount = totalCount,
            buckets = buckets,
            p99bound = p99,
            p999bound = p999)
    }

    data class Snapshot(
        val averageDelayMs: Double,
        val maxDelayMs: Long,
        val totalCount: Long,
        val buckets: Array<Pair<Long, Long>>,
        val p99bound: Long,
        val p999bound: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Snapshot

            if (averageDelayMs != other.averageDelayMs) return false
            if (maxDelayMs != other.maxDelayMs) return false
            if (totalCount != other.totalCount) return false
            if (!buckets.contentEquals(other.buckets)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = averageDelayMs.hashCode()
            result = 31 * result + maxDelayMs.hashCode()
            result = 31 * result + totalCount.hashCode()
            result = 31 * result + buckets.contentHashCode()
            return result
        }
    }
}

class PacketDelayStats : DelayStats() {
    fun addPacket(packetInfo: PacketInfo) {
        val delayMs = if (packetInfo.receivedTime > 0) {
            System.currentTimeMillis() - packetInfo.receivedTime
        } else {
            -1
        }
        addDelay(delayMs)
    }
}
