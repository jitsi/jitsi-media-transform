/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.nlj.rtp

import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.PacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.UnreceivedPacketReport
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging2.Logger

/**
 * Implements transport-cc functionality.
 *
 * See https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 *
 * @author Boris Grozev
 * @author Julian Chukwu
 * @author George Politis
 */
class TransportCcEngine(
    private val bandwidthEstimator: BandwidthEstimator,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : RtcpListener {

    /**
     * The [Logger] used by this instance for logging output.
     */
    private val logger: Logger = parentLogger.createChildLogger(javaClass.name)

    /**
     * Used to synchronize access to [.sentPacketDetails].
     */
    private val sentPacketsSyncRoot = Any()

    /**
     * The reference time of the remote clock. This is used to rebase the
     * arrival times in the TCC packets to a meaningful time base (that of the
     * sender). This is technically not necessary and it's done for convenience.
     */
    private var remoteReferenceTime: Instant = NEVER

    /**
     * Local time to map to the reference time of the remote clock. This is used
     * to rebase the arrival times in the TCC packets to a meaningful time base
     * (that of the sender). This is technically not necessary and it's done for
     * convenience.
     */
    private var localReferenceTime: Instant = NEVER

    /**
     * Holds a key value pair of the packet sequence number and the
     * packet stats.
     */
    private val sentPacketStats = LRUCache<Int, BandwidthEstimator.PacketStats>(MAX_OUTGOING_PACKETS_HISTORY)

    private val missingPacketStatsSeqNums = mutableListOf<Int>()

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    fun onRttUpdate(rtt: Duration) {
        val now = clock.instant()
        bandwidthEstimator.onRttUpdate(now, rtt)
    }

    override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Long) {
        if (rtcpPacket is RtcpFbTccPacket) {
            tccReceived(rtcpPacket)
        }
    }

    private fun tccReceived(tccPacket: RtcpFbTccPacket) {
        val now = clock.instant()
        var currArrivalTimestamp = Instant.ofEpochMilli(tccPacket.GetBaseTimeUs() / 1000)
        if (remoteReferenceTime == NEVER) {
            remoteReferenceTime = currArrivalTimestamp
            localReferenceTime = now
        }

        // We have to remember the oldest known sequence number here, as we
        // remove from sentPacketDetails inside this loop
        val oldestKnownSeqNum = synchronized(sentPacketsSyncRoot) { sentPacketStats.oldestEntry() }
        for (packetReport in tccPacket) {
            val tccSeqNum = packetReport.seqNum
            val packetStats = synchronized(sentPacketsSyncRoot) {
                sentPacketStats.remove(tccSeqNum)
            }

            if (packetStats == null) {
                if (packetReport is ReceivedPacketReport) {
                    missingPacketStatsSeqNums.add(tccSeqNum)
                }
                continue
            }

            when (packetReport) {
                is UnreceivedPacketReport ->
                    bandwidthEstimator.processPacketLoss(now, packetStats)

                is ReceivedPacketReport -> {
                    currArrivalTimestamp += packetReport.deltaDuration

                    val arrivalTimeInLocalClock = currArrivalTimestamp - Duration.between(localReferenceTime, remoteReferenceTime)

                    bandwidthEstimator.processPacketArrival(
                        now, packetStats, arrivalTimeInLocalClock)
                }
            }
        }
        bandwidthEstimator.feedbackComplete(now)

        if (missingPacketStatsSeqNums.isNotEmpty()) {
            logger.warn("TCC packet contained received sequence numbers: " +
                "${tccPacket.iterator().asSequence()
                    .filterIsInstance<ReceivedPacketReport>()
                    .map(PacketReport::seqNum)
                    .joinToString()}. " +
                "Couldn't find packet stats for the seq nums: ${missingPacketStatsSeqNums.joinToString()}. " +
                (oldestKnownSeqNum?.let { "Oldest known seqNum was $it." } ?: run { "Sent packet details map was empty." }))
            missingPacketStatsSeqNums.clear()
        }
    }

    fun mediaPacketSent(
        tccSeqNum: Int,
        length: DataSize,
        isRtx: Boolean = false,
        isProbing: Boolean = false
    ) {
        synchronized(sentPacketsSyncRoot) {
            val now = clock.instant()
            val modSeq = tccSeqNum and 0xFFFF
            val stats = BandwidthEstimator.PacketStats(modSeq, length, now)
            stats.isRtx = isRtx
            stats.isProbing = isProbing
            sentPacketStats.put(modSeq, stats)
        }
    }

    companion object {
        /**
         * The maximum number of received packets and their timestamps to save.
         *
         * XXX this is an uninformed value.
         */
        private const val MAX_OUTGOING_PACKETS_HISTORY = 1000
    }

    private fun <K, V> LRUCache<K, V>.oldestEntry(): K? {
        with(iterator()) {
            if (hasNext()) {
                return next().key
            }
        }
        return null
    }
}
