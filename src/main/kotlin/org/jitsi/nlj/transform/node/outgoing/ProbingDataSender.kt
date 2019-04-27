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

package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.Event
import org.jitsi.nlj.EventHandler
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.SetLocalSsrcEvent
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.VideoPayloadType
import org.jitsi.nlj.rtp.PaddingVideoPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.PacketCache
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.utils.MediaType
import java.util.Random

/**
 * [ProbingDataSender] currently supports probing via 2 methods:
 * 1) retransmitting previous packets via RTX via [sendRedundantDataOverRtx].
 * 2) If RTX is not available, or, not enough packets to retransmit are available, we
 * can send empty media packets using the bridge's ssrc
 *
 */
class ProbingDataSender(
    private val packetCache: PacketCache,
    private val rtxDataSender: PacketHandler,
    private val garbageDataSender: PacketHandler
) : EventHandler, NodeStatsProducer {

    private val logger = getLogger(this.javaClass)

    private var rtxSupported = false
    private val videoPayloadTypes = mutableSetOf<VideoPayloadType>()
    private var localVideoSsrc: Long? = null

    // Stats
    private var numProbingBytesSentRtx: Int = 0
    private var numProbingBytesSentDummyData: Int = 0

    fun sendProbing(mediaSsrc: Long, numBytes: Int): Int {
        var totalBytesSent = 0

        if (rtxSupported) {
            val rtxBytesSent = sendRedundantDataOverRtx(mediaSsrc, numBytes)
            numProbingBytesSentRtx += rtxBytesSent
            totalBytesSent += rtxBytesSent
            logger.cdebug { "Sent $rtxBytesSent bytes of probing data over RTX" }
        }
        if (totalBytesSent < numBytes) {
            val dummyBytesSent = sendDummyData(numBytes - totalBytesSent)
            numProbingBytesSentDummyData += dummyBytesSent
            totalBytesSent += dummyBytesSent
            logger.cdebug { "Sent $dummyBytesSent bytes of probing data sent as dummy data" }
        }

        return totalBytesSent
    }

    /**
     * Using the RTX stream associated with [mediaSsrc], send [numBytes] of data
     * by re-transmitting previously sent packets from the outgoing packet cache.
     * Returns the number of bytes transmitted
     */
    private fun sendRedundantDataOverRtx(mediaSsrc: Long, numBytes: Int): Int {
        var bytesSent = 0
        val lastNPackets = packetCache.getMany(mediaSsrc, numBytes)

        if (lastNPackets.isEmpty()) {
            return bytesSent
        }

        // XXX this constant (2) is not great, however the final place of the stream
        // protection strategy is not clear at this point so I expect the code
        // will change before taking its final form.
        val packetsToResend = mutableListOf<PacketInfo>()
        for (i in 0 until 2) {
            val lastNPacketIter = lastNPackets.iterator()

            while (lastNPacketIter.hasNext()) {
                val packet = lastNPacketIter.next()
                val packetLen = packet.length
                if (bytesSent + packetLen > numBytes) {
                    // We don't have enough 'room' to send this packet; we're done
                    break
                }
                bytesSent += packetLen
                // The node after this one will be the RetransmissionSender, which handles
                // encapsulating packets as RTX (with the proper ssrc and payload type) so we
                // just need to find the packets to retransmit and forward them to the next node
                // TODO(brian): do we need to clone it here?
                packetsToResend.add(PacketInfo(packet.clone()))
            }
        }
        // TODO(brian): we're in a thread context mess here.  we'll be sending these out from the bandwidthprobing
        // context (or whoever calls this) which i don't think we want.  Need look at getting all the pipeline
        // work posted to one thread so we don't have to worry about concurrency nightmares
        if (packetsToResend.isNotEmpty()) {
            packetsToResend.forEach { rtxDataSender.processPacket(it) }
        }

        return bytesSent
    }

    private var currDummyTimestamp = Random().nextLong() and 0xFFFFFFFF
    private var currDummySeqNum = Random().nextInt(0xFFFF)

    private fun sendDummyData(numBytes: Int): Int {
        var bytesSent = 0
        val pt = videoPayloadTypes.firstOrNull() ?: return bytesSent
        val senderSsrc = localVideoSsrc ?: return bytesSent
        // TODO(brian): shouldn't this take into account numBytes? what if it's less than
        // the size of one dummy packet?
        val packetLength = RtpHeader.FIXED_HEADER_SIZE_BYTES + 0xFF
        val numPackets = (numBytes / packetLength) + 1 /* account for the mod */
        for (i in 0 until numPackets) {
            val paddingPacket = PaddingVideoPacket.create(packetLength)
            paddingPacket.payloadType = pt.pt.toPositiveInt()
            paddingPacket.ssrc = senderSsrc
            paddingPacket.timestamp = currDummyTimestamp
            paddingPacket.sequenceNumber = currDummySeqNum
            garbageDataSender.processPacket(PacketInfo(paddingPacket))

            currDummySeqNum++
            bytesSent += packetLength
        }
        currDummyTimestamp += 3000

        return bytesSent
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                if (event.payloadType is RtxPayloadType) {
                    logger.cdebug { "RTX payload type signaled, enabling RTX probing" }
                    rtxSupported = true
                } else if (event.payloadType is VideoPayloadType) {
                    videoPayloadTypes.add(event.payloadType)
                }
            }
            is RtpPayloadTypeClearEvent -> {
                rtxSupported = false
                videoPayloadTypes.clear()
            }
            is SetLocalSsrcEvent -> {
                if (MediaType.VIDEO == event.mediaType) {
                    logger.cdebug { "Setting video ssrc to ${event.ssrc}" }
                    localVideoSsrc = event.ssrc
                }
            }
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Probing data sender").apply {
            addNumber("num_bytes_of_probing_data_sent_as_rtx", numProbingBytesSentRtx)
            addNumber("num_bytes_of_probing_data_sent_as_dummy", numProbingBytesSentDummyData)
            addBoolean("rtxSupported", rtxSupported)
            addString("localVideoSsrc", localVideoSsrc?.toString() ?: "null")
            addString("currDummyTimestamp", currDummyTimestamp.toString())
            addString("currDummySeqNum", currDummySeqNum.toString())
        }
    }
}
