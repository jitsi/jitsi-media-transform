/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.rtp.RtxPacket
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.RtxPayloadTypeStore
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cerror
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Handle incoming RTX packets to strip the RTX information and make them
 * look like their original packets.
 * https://tools.ietf.org/html/rfc4588
 */
class RtxHandler(
    streamInformationStore: ReadOnlyStreamInformationStore
) : TransformerNode("RTX handler") {
    private var numPaddingPacketsReceived = 0
    private var numRtxPacketsReceived = 0
    /**
     * Map the RTX stream ssrcs to their corresponding media ssrcs
     */
    // TODO: this information should probably live alongside the associated payload types
    // in rtxpayloadstore (probably with a different name)
    private val associatedSsrcs: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    private val rtxPayloadTypeStore = RtxPayloadTypeStore(logger)

    init {
        streamInformationStore.onRtpPayloadTypeEvent(rtxPayloadTypeStore.mapEventHandler)
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        if (rtxPayloadTypeStore.isRtx(rtpPacket.payloadType) &&
            associatedSsrcs.containsKey(rtpPacket.ssrc)) {
//          logger.cdebug {
//             "Received RTX packet: ssrc ${rtxPacket.header.ssrc}, seq num: ${rtxPacket.header.sequenceNumber} " +
//             "rtx payload size: ${rtxPacket.payload.limit()}, padding size: ${rtxPacket.getPaddingSize()} " +
//             "buffer:\n${rtxPacket.getBuffer().toHex()}" }
            if (rtpPacket.payloadLength - rtpPacket.paddingSize < 2) {
                logger.cdebug { "RTX packet is padding, ignore" }
                numPaddingPacketsReceived++
                packetDiscarded(packetInfo)
                return null
            }

            val originalSeqNum = RtxPacket.getOriginalSequenceNumber(rtpPacket)
            val originalPt = rtxPayloadTypeStore.getOriginalPayloadType(rtpPacket.payloadType) ?: run {
                logger.cerror { "Error finding original payload type for RTX " +
                    "payload type ${rtpPacket.payloadType.toPositiveInt()}" }
                packetDiscarded(packetInfo)
                return null
            }
            val originalSsrc = associatedSsrcs[rtpPacket.ssrc]!!

            // Move the payload 2 bytes to the left
            RtxPacket.removeOriginalSequenceNumber(rtpPacket)
            rtpPacket.sequenceNumber = originalSeqNum
            rtpPacket.payloadType = originalPt
            rtpPacket.ssrc = originalSsrc

            logger.cdebug { "Recovered RTX packet.  Original packet: $originalSsrc $originalSeqNum" }
            numRtxPacketsReceived++
            packetInfo.resetPayloadVerification()
            return packetInfo
        } else {
            return packetInfo
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SsrcAssociationEvent -> {
                if (event.type == SsrcAssociationType.RTX) {
                    logger.cdebug { "Associating RTX ssrc ${event.secondarySsrc} with primary ${event.primarySsrc}" }
                    associatedSsrcs[event.secondarySsrc] = event.primarySsrc
                }
            }
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_rtx_packets_received", numRtxPacketsReceived)
            addNumber("num_padding_packets_received", numPaddingPacketsReceived)
            addString("rtx_payload_type_associations", rtxPayloadTypeStore.associatedPayloadTypes.toString())
        }
    }

    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }
}
