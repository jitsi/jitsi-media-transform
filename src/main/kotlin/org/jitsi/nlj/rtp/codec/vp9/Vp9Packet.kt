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

package org.jitsi.nlj.rtp.codec.vp9

import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.utils.logging2.cwarn
import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment
import org.jitsi.utils.logging2.createLogger
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer
import kotlin.properties.Delegates

/**
 * If this [Vp9Packet] instance is being created via a clone,
 * we've already parsed the packet itself and determined whether
 * or not its a keyframe and what its spatial layer index is,
 * so the constructor allows passing in those values if
 * they're already known.  If they're null, this instance
 * will do the parsing itself.
 */
class Vp9Packet private constructor (
    buffer: ByteArray,
    offset: Int,
    length: Int,
    isKeyframe: Boolean?,
    isStartOfFrame: Boolean?,
    encodingIndex: Int?,
    height: Int?,
    pictureId: Int?,
    TL0PICIDX: Int?
) : ParsedVideoPacket(buffer, offset, length, encodingIndex) {

    constructor(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ) : this(buffer, offset, length,
        isKeyframe = null,
        isStartOfFrame = null,
        encodingIndex = null,
        height = null,
        pictureId = null,
        TL0PICIDX = null
    )

    /** Due to the format of the VP9 payload, this value is only reliable for packets where [isStartOfFrame] is true. */
    override val isKeyframe: Boolean = isKeyframe ?: DePacketizer.VP9PayloadDescriptor.isKeyFrame(this.buffer, payloadOffset, payloadLength)

    override val isStartOfFrame: Boolean = isStartOfFrame ?: DePacketizer.VP9PayloadDescriptor.isStartOfFrame(buffer, payloadOffset, payloadLength)

    /** End of VP9 frame is the marker bit. */
    /* TODO: frame/picture distinction here */
    override val isEndOfFrame: Boolean
        /** This uses [get] rather than initialization because [isMarked] is a var. */
        get() = isMarked

    val hasLayerIndices = DePacketizer.VP9PayloadDescriptor.hasLayerIndices(buffer, payloadOffset, payloadLength)

    val hasPictureId = DePacketizer.VP9PayloadDescriptor.hasPictureId(buffer, payloadOffset, payloadLength)

    val hasExtendedPictureId = DePacketizer.VP9PayloadDescriptor.hasExtendedPictureId(buffer, payloadOffset, payloadLength)

    val hasTL0PICIDX = DePacketizer.VP9PayloadDescriptor.hasTL0PICIDX(buffer, payloadOffset, payloadLength)

    var TL0PICIDX: Int by Delegates.observable(TL0PICIDX ?: DePacketizer.VP9PayloadDescriptor.getTL0PICIDX(buffer, payloadOffset, payloadLength)) {
        _, _, newValue ->
            if (newValue != -1 && !DePacketizer.VP9PayloadDescriptor.setTL0PICIDX(
                    buffer, payloadOffset, payloadLength, newValue)) {
                logger.cwarn { "Failed to set the TL0PICIDX of a VP9 packet." }
            }
        }

    var pictureId: Int by Delegates.observable(pictureId ?: DePacketizer.VP9PayloadDescriptor.getPictureId(buffer, payloadOffset, payloadLength)) {
        _, _, newValue ->
            if (!DePacketizer.VP9PayloadDescriptor.setExtendedPictureId(
                    buffer, payloadOffset, payloadLength, newValue)) {
                logger.cwarn { "Failed to set the picture id of a VP9 packet." }
            }
        }

    val temporalLayerIndex: Int = DePacketizer.VP9PayloadDescriptor.getTemporalLayerIndex(buffer, payloadOffset, payloadLength)

    override var height: Int = height ?: -1

    /**
     * For [Vp9Packet] the payload excludes the VP9 Payload Descriptor.
     */
    override val payloadVerification: String
        get() {
            val rtpPayloadLength = payloadLength
            val rtpPayloadOffset = payloadOffset
            val VP9pdSize = DePacketizer.VP9PayloadDescriptor.getSize(buffer, rtpPayloadOffset, rtpPayloadLength)
            val VP9PayloadLength = rtpPayloadLength - VP9pdSize
            val hashCode = buffer.hashCodeOfSegment(payloadOffset + VP9pdSize, rtpPayloadOffset + rtpPayloadLength)
            return "type=VP9Packet len=$VP9PayloadLength hashCode=$hashCode"
        }

    override fun clone(): Vp9Packet {
        return Vp9Packet(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            isKeyframe = isKeyframe,
            isStartOfFrame = isStartOfFrame,
            encodingIndex = qualityIndex,
            height = height,
            pictureId = pictureId,
            TL0PICIDX = TL0PICIDX
        )
    }

    companion object {
        private val logger = createLogger()
    }
}
