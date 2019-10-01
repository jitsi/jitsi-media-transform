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

import org.jitsi.nlj.rtcp.RtcpListener
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
    parentLogger: Logger
) : RemoteBitrateObserver, RtcpListener {

    /**
     * The [Logger] used by this instance for logging output.
     */
    private val logger: Logger

    /**
     * Used to synchronize access to [.sentPacketDetails].
     */
    private val sentPacketsSyncRoot = Any()

    /**
     * The reference time of the remote clock. This is used to rebase the
     * arrival times in the TCC packets to a meaningful time base (that of the
     * sender). This is technically not necessary and it's done for convenience.
     */
    private var remoteReferenceTimeMs: Long = -1

    /**
     * Local time to map to the reference time of the remote clock. This is used
     * to rebase the arrival times in the TCC packets to a meaningful time base
     * (that of the sender). This is technically not necessary and it's done for
     * convenience.
     */
    private var localReferenceTimeMs: Long = -1

    /**
     * Holds a key value pair of the packet sequence number and an object made
     * up of the packet send time and the packet size.
     */
    private val sentPacketDetails = LRUCache<Int, PacketDetail>(MAX_OUTGOING_PACKETS_HISTORY)

    /**
     * Used for estimating the bitrate from RTCP TCC feedback packets
     */
    private val bitrateEstimatorAbsSendTime: RemoteBitrateEstimatorAbsSendTime

    init {
        logger = parentLogger.createChildLogger(javaClass.name)
        bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(this, diagnosticContext, logger)
    }

    /**
     * Called when an RTP sender has a new round-trip time estimate.
     */
    fun onRttUpdate(avgRttMs: Long, maxRttMs: Long) {
        val nowMs = System.currentTimeMillis()
        bitrateEstimatorAbsSendTime.onRttUpdate(nowMs, avgRttMs, maxRttMs)
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
        val nowMs = System.currentTimeMillis()
        if (remoteReferenceTimeMs == -1L) {
            remoteReferenceTimeMs = tccPacket.GetBaseTimeUs() / 1000
            localReferenceTimeMs = nowMs
        }
        var currArrivalTimestampMs = tccPacket.GetBaseTimeUs() / 1000.0

        for (receivedPacket in tccPacket) {
            val tccSeqNum = receivedPacket.seqNum
            val deltaMs = receivedPacket.deltaTicks / 4.0
            currArrivalTimestampMs += deltaMs

            val packetDetail: PacketDetail?
            synchronized(sentPacketsSyncRoot) {
                packetDetail = sentPacketDetails.remove(tccSeqNum)
            }

            if (packetDetail == null) {
                logger.warn("Couldn't find packet detail for $tccSeqNum.")
                continue
            }

            val arrivalTimeMsInLocalClock = currArrivalTimestampMs.toLong() - remoteReferenceTimeMs + localReferenceTimeMs
            val sendTime24bitsInLocalClock = RemoteBitrateEstimatorAbsSendTime
                .convertMsTo24Bits(packetDetail.packetSendTimeMs)

            bitrateEstimatorAbsSendTime.incomingPacketInfo(
                nowMs,
                arrivalTimeMsInLocalClock,
                sendTime24bitsInLocalClock,
                packetDetail.packetLength,
                tccPacket.mediaSourceSsrc
            )
        }
    }

    fun mediaPacketSent(tccSeqNum: Int, length: Int) {
        synchronized(sentPacketsSyncRoot) {
            val now = System.currentTimeMillis()
            sentPacketDetails.put(
                tccSeqNum and 0xFFFF,
                PacketDetail(length, now))
        }
    }

    /**
     * [PacketDetail] is an object that holds the
     * length(size) of the packet in [.packetLength]
     * and the time stamps of the outgoing packet
     * in [.packetSendTimeMs]
     */
    private inner class PacketDetail internal constructor(internal var packetLength: Int, internal var packetSendTimeMs: Long)

    companion object {
        /**
         * The maximum number of received packets and their timestamps to save.
         *
         * XXX this is an uninformed value.
         */
        private val MAX_OUTGOING_PACKETS_HISTORY = 1000
    }
}
