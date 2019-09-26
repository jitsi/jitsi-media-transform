/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Duration
import java.time.Instant
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize

/**
 * An abstract interface to a bandwidth estimation algorithm.
 *
 * The invoker of the algorithm will periodically call [processPacketArrival]
 * and/or [processPacketLoss] as it learns information about packets
 * that have traversed the network.
 *
 * All bandwidths/bitrates are in bits per second.
 */
interface BandwidthEstimator {
    /** The name of the algorithm implemented by this [BandwidthEstimator]. */
    val algorithmName: String

    /** The initial bandwidth estimate. */
    var initBw: Bandwidth

    /** The minimum bandwidth the estimator will return. */
    var minBw: Bandwidth

    /** The maximum bandwidth the estimator will return. */
    var maxBw: Bandwidth

    /**
     * Inform the bandwidth estimator about a packet that has arrived at its
     * destination.
     *
     * This function will be called at most once for any value of [seq];
     * however, it may be called after a call to [processPacketLoss] for the
     * same [seq] value, if a packet is delayed.
     *
     * It is possible (e.g., if feedback was lost) that neither
     * [processPacketArrival] nor [processPacketLoss] is called for a given [seq].
     *
     * The clocks reported by [now], [sendTime], and [recvTime] do not
     * necessarily have any relationship to each other, but must be consistent
     * within themselves across all calls to functions of this [BandwidthEstimator].
     *
     * @param[now] The current time, when this function is called.
     * @param[sendTime] The time the packet was sent, if known, or null.
     * @param[recvTime] The time the packet was received, if known, or null.
     * @param[seq] A 16-bit sequence number of packets processed by this
     *  [BandwidthEstimator].
     * @param[size] The size of the packet.
     * @param[ecn] The ECN markings with which the packet was received.
     */
    fun processPacketArrival(
        now: Instant,
        sendTime: Instant?,
        recvTime: Instant?,
        seq: Int,
        size: DataSize,
        ecn: Byte = 0
    )

    /**
     * Inform the bandwidth estimator that a packet was lost.
     *
     * @param[now] The current time, when this function is called.
     * @param[sendTime] The time the packet was sent, if known, or null.
     * @param[seq] A 16-bit sequence number of packets processed by this
     *  [BandwidthEstimator].
     */
    fun processPacketLoss(now: Instant, sendTime: Instant?, seq: Int)

    /**
     * Inform the bandwidth estimator about a new round-trip time value
     */
    fun onRttUpdate(now: Instant, newRtt: Duration)

    /** Get the estimator's current estimate of the available bandwidth.
     *
     * @param[now] The current time, when this function is called.
     */
    fun getCurrentBw(now: Instant): Bandwidth

    /** Get the current statistics related to this estimator. */
    fun getStats(): NodeStatsBlock

    /** Reset the estimator to its initial state. */
    fun reset(): Unit
}
