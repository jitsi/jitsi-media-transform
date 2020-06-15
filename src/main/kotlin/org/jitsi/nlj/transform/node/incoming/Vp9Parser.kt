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

package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Vp9Packet] fields are not able to be determined by looking at a single VP8 packet (for example the scalability
 * structure is only carried in keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
class Vp9Parser(
    parentLogger: Logger
) : ObserverNode("Vp9 parser") {
    private val logger = createChildLogger(parentLogger)
    // Stats
    private var numKeyframes: Int = 0
    private var sources: Array<MediaSourceDesc> = arrayOf()

    override fun observe(packetInfo: PacketInfo) {
        val vp9Packet = packetInfo.packet as Vp9Packet

        if (vp9Packet.hasScalabilityStructure) {
            // TODO: handle case where new SS is from a packet older than the
            // latest SS we've seen.
            val (src, enc) = findRtpEncodingDesc(vp9Packet)
            if (src != null && enc != null) {
                val newEnc = vp9Packet.getScalabilityStructure(eid = enc.layers[0].eid)
                if (newEnc != null) {
                    src.setEncodingLayers(newEnc.layers, vp9Packet.ssrc)
                }
            }
        }
        /* VP9 marks keyframes in every packet of the keyframe - only count the start of the frame so the count is correct. */
        /* Alternately we could keep track of keyframes we've already seen, by timestamp, but that seems unnecessary. */
        if (vp9Packet.isKeyframe && vp9Packet.isStartOfFrame) {
            logger.cdebug { "Received a keyframe for ssrc ${vp9Packet.ssrc} ${vp9Packet.sequenceNumber}" }
            numKeyframes++
        }

        if (!vp9Packet.hasPictureId) {
            logger.cinfo { "Packet $vp9Packet does not have picture ID.  Packet data: ${vp9Packet.toHex(80)}" }
        } else if (!vp9Packet.hasExtendedPictureId) {
            logger.cinfo { "Packet $vp9Packet has 7-bit (short) picture ID.  Packet data: ${vp9Packet.toHex(80)}" }
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                sources = event.mediaSourceDescs
            }
        }
        super.handleEvent(event)
    }

    /* TODO: this return value is clumsy */
    private fun findRtpEncodingDesc(packet: VideoRtpPacket): Pair<MediaSourceDesc?, RtpEncodingDesc?> {
        for (source in sources) {
            source.findRtpEncodingDesc(packet.ssrc)?.let {
                return Pair(source, it)
            }
        }
        return Pair(null, null)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_keyframes", numKeyframes)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
