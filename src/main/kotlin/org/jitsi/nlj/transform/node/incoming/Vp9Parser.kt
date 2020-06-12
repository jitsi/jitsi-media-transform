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
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.cinfo
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Vp9Packet] fields are not able to be determined by looking at a single VP8 packet (for example the scalability
 * structure is only carried in keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 *
 * TODO(brian): This class shouldn't really be a [ModifierNode], but since we put it in-line in the video
 * receive pipeline (as opposed to demuxing based on payload type and routing only known VP8 packets to
 * it), it's the most appropriate node type for now.
 */
class Vp9Parser(
    parentLogger: Logger
) : ModifierNode("Vp9 parser") {
    private val logger = createChildLogger(parentLogger)
    // Stats
    private var numKeyframes: Int = 0
    private var sources: Array<MediaSourceDesc> = arrayOf()

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        if (videoRtpPacket is Vp9Packet) {
            // If this was part of a keyframe, it will have already had it set
            if (videoRtpPacket.hasScalabilityStructure) {
                // TODO: handle case where new height is from a packet older than the
                // latest height we've seen.
                val enc = findRtpEncodingDesc(videoRtpPacket)
                if (enc != null) {
                    val newEnc = videoRtpPacket.getScalabilityStructure(eid = enc.layers[0].eid)
                    if (newEnc != null) {
                        enc.layers = newEnc.layers
                    }
                }
            }
            if (videoRtpPacket.isKeyframe) {
                logger.cdebug { "Received a keyframe for ssrc ${videoRtpPacket.ssrc} ${videoRtpPacket.sequenceNumber}" }
                numKeyframes++
            }

            if (!videoRtpPacket.hasPictureId) {
                logger.cinfo { "Packet $videoRtpPacket does not have picture ID.  Packet data: ${videoRtpPacket.toHex(80)}" }
            } else if (!videoRtpPacket.hasExtendedPictureId) {
                logger.cinfo { "Packet $videoRtpPacket has 7-bit (short) picture ID.  Packet data: ${videoRtpPacket.toHex(80)}" }
            }
        }

        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                sources = event.mediaSourceDescs
            }
        }
        super.handleEvent(event)
    }

    private fun findRtpEncodingDesc(packet: VideoRtpPacket): RtpEncodingDesc? {
        for (source in sources) {
            source.findRtpEncodingDesc(packet.ssrc)?.let {
                return it
            }
        }
        return null
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_keyframes", numKeyframes)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
