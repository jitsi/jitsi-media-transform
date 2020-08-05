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
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.StateChangeLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.cdebug
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

    private val pictureIdState = StateChangeLogger("missing picture id", logger)
    private val extendedPictureIdState = StateChangeLogger("missing extended picture ID", logger)
    private var numSpatialLayers = -1

    /** Encodings we've actually seen.  Used to clear out inferred-from-signaling encoding information. */
    private val ssrcsSeen = HashSet<Long>()

    override fun observe(packetInfo: PacketInfo) {
        val vp9Packet = packetInfo.packet as Vp9Packet

        ssrcsSeen.add(vp9Packet.ssrc)

        if (vp9Packet.hasScalabilityStructure) {
            // TODO: handle case where new SS is from a packet older than the
            //  latest SS we've seen.
            val packetSpatialLayers = vp9Packet.scalabilityStructureNumSpatial
            if (packetSpatialLayers != -1) {
                if (numSpatialLayers != -1 && numSpatialLayers != vp9Packet.scalabilityStructureNumSpatial) {
                    packetInfo.layeringChanged = true
                }
                numSpatialLayers = packetSpatialLayers
            }
            val srcAndEnc = findRtpEncodingDesc(vp9Packet)
            if (srcAndEnc != null) {
                val (src, enc) = srcAndEnc
                val newEnc = vp9Packet.getScalabilityStructure(eid = enc.layers[0].eid)
                if (newEnc != null) {
                    src.setEncodingLayers(newEnc.layers, vp9Packet.ssrc)
                }
                for (otherEnc in src.rtpEncodings) {
                    if (!ssrcsSeen.contains(otherEnc.primarySSRC)) {
                        src.setEncodingLayers(emptyArray(), otherEnc.primarySSRC)
                    }
                }
            }

            /* TODO: we need a way to restore the encoding desc's old layer set if it switches back to some other codec
             *  (i.e. VP8)
             */
        }
        /* VP9 marks keyframes in every packet of the keyframe - only count the start of the frame so the count is correct. */
        /* Alternately we could keep track of keyframes we've already seen, by timestamp, but that seems unnecessary. */
        if (vp9Packet.isKeyframe && vp9Packet.isStartOfFrame) {
            logger.cdebug { "Received a keyframe for ssrc ${vp9Packet.ssrc} ${vp9Packet.sequenceNumber}" }
            numKeyframes++
        }
        if (vp9Packet.spatialLayerIndex > 0 && vp9Packet.isInterPicturePredicted) {
            /* Check if this layer is using K-SVC. */
            /* TODO: should we somehow track the keyframe layers separately in
                the layer descriptions, to get the bitrates more accurate? */
            findRtpLayerDesc(vp9Packet)?.useSoftDependencies = vp9Packet.usesInterLayerDependency
        }

        pictureIdState.setState(vp9Packet.hasPictureId, vp9Packet) {
            "Packet Data: ${vp9Packet.toHex(80)}"
        }
        extendedPictureIdState.setState(vp9Packet.hasExtendedPictureId, vp9Packet) {
            "Packet Data: ${vp9Packet.toHex(80)}"
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
    private fun findRtpEncodingDesc(packet: VideoRtpPacket): Pair<MediaSourceDesc, RtpEncodingDesc>? {
        for (source in sources) {
            source.findRtpEncodingDesc(packet.ssrc)?.let {
                return Pair(source, it)
            }
        }
        return null
    }

    private fun findRtpLayerDesc(packet: VideoRtpPacket): RtpLayerDesc? {
        for (source in sources) {
            source.findRtpLayerDesc(packet)?.let {
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
