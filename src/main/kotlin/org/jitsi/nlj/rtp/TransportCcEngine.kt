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
import java.time.temporal.ChronoUnit
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.NEVER
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateObserver

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
    diagnosticContext: DiagnosticContext,
    private val remoteBitrateObserver: RemoteBitrateObserver,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC()
) : RemoteBitrateObserver, RtcpListener {

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
     * Holds a key value pair of the packet sequence number and an object made
     * up of the packet send time and the packet size.
     */
    private val sentPacketDetails = LRUCache<Int, PacketDetail>(MAX_OUTGOING_PACKETS_HISTORY)

    /**
     * Used for estimating the bitrate from RTCP TCC feedback packets
     */
    private val bitrateEstimatorAbsSendTime =
        RemoteBitrateEstimatorAbsSendTime(this, diagnosticContext, logger)

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    fun onRttUpdate(avgRttMs: Long, maxRttMs: Long) {
        val now = clock.instant()
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), avgRttMs, maxRttMs)
    }

    /**
     * Called when a receive channel group has a new bitrate estimate for the
     * incoming streams.
     *
     * @param ssrcs
     * @param bitrate
     */
    override fun onReceiveBitrateChanged(ssrcs: Collection<Long>, bitrate: Long) {
        remoteBitrateObserver.onReceiveBitrateChanged(ssrcs, bitrate)
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

        for (receivedPacket in tccPacket) {
            if (!receivedPacket.received)
                continue
            val tccSeqNum = receivedPacket.seqNum
            val delta = Duration.of(receivedPacket.deltaTicks * 250L, ChronoUnit.MICROS)
            currArrivalTimestamp += delta

            val packetDetail: PacketDetail?
            synchronized(sentPacketsSyncRoot) {
                packetDetail = sentPacketDetails.remove(tccSeqNum)
            }

            if (packetDetail == null) {
                logger.warn("Couldn't find packet detail for $tccSeqNum.")
                continue
            }

            val arrivalTimeInLocalClock = currArrivalTimestamp - Duration.between(localReferenceTime, remoteReferenceTime)

            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                now.toEpochMilli(),
                arrivalTimeInLocalClock.toEpochMilli(),
                packetDetail.packetSendTime.toEpochMilli(),
                packetDetail.packetLength.bytes.toInt(),
                tccPacket.mediaSourceSsrc
            )
        }
    }

    fun mediaPacketSent(tccSeqNum: Int, length: DataSize) {
        synchronized(sentPacketsSyncRoot) {
            val now = clock.instant()
            sentPacketDetails.put(
                tccSeqNum and 0xFFFF,
                PacketDetail(length, now))
        }
    }

    /**
     * [PacketDetail] is an object that holds the
     * length(size) of the packet in [packetLength]
     * and the time stamps of the outgoing packet
     * in [packetSendTime]
     */
    private data class PacketDetail internal constructor(internal var packetLength: DataSize, internal var packetSendTime: Instant)

    companion object {
        /**
         * The maximum number of received packets and their timestamps to save.
         *
         * XXX this is an uninformed value.
         */
        private const val MAX_OUTGOING_PACKETS_HISTORY = 1000
    }
}
