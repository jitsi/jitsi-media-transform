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
package org.jitsi.nlj

import java.util.Arrays
import org.jitsi.nlj.RtpLayerDesc.Companion.getEncodingId
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.utils.ArrayUtils

/**
 * Represents a collection of [RtpLayerDesc]s that encode the same
 * media source. This specific implementation provides webrtc simulcast stream
 * suspension detection.
 *
 * @author George Politis
 */
class MediaSourceDesc
@JvmOverloads constructor(
    /**
     * The [RtpEncodingDesc]s that this [MediaSourceDesc]
     * possesses, ordered by their subjective quality from low to high.
     */
    val rtpEncodings: Array<RtpEncodingDesc>,
    /**
     * A string which identifies the owner of this source (e.g. the endpoint
     * which is the sender of the source).
     */
    val owner: String? = null
) {
    /**
     * Allow the lookup of a layer by the encoding id of a received packet.
     */
    private val layersById: MutableMap<Long, RtpLayerDesc> = HashMap()

    /**
     * Allow the lookup of a layer by index.
     */
    private val layersByIndex: MutableMap<Int, RtpLayerDesc> = HashMap()

    /**
     * Update the layer cache.  Should be synchronized on [this].
     */
    private fun updateLayerCache() {
        layersById.clear()
        layersByIndex.clear()

        for (encoding in rtpEncodings) {
            for (layer in encoding.layers) {
                layersById[encoding.encodingId(layer)] = layer
                layersByIndex[layer.index] = layer
            }
        }
    }

    init { updateLayerCache() }

    /**
     * Gets the last "stable" bitrate (in bps) of the encoding of the specified
     * index. The "stable" bitrate is measured on every new frame and with a
     * 5000ms window.
     *
     * @return the last "stable" bitrate (bps) of the encoding at the specified
     * index.
     */
    fun getBitrateBps(nowMs: Long, idx: Int): Long {
        val layer = getRtpLayerByQualityIdx(idx) ?: return 0

        // TODO: previous code returned a lower layer if this layer's bitrate was 0.
        // Do we still need this?
        return layer.getBitrateBps(nowMs)
    }

    fun hasRtpLayers(): Boolean =
        synchronized(this) {
            !layersByIndex.isEmpty()
        }

    /**
     * Get an iterator over the source's RTP layers, in quality order.  Should be synchronized on [this].
     */
    val rtpLayers: Iterable<RtpLayerDesc>
        get() = Iterable { Arrays.stream(rtpEncodings).flatMap { e: RtpEncodingDesc -> Arrays.stream(e.layers) }.iterator() }

    fun numRtpLayers(): Int =
        synchronized(this) {
            layersByIndex.size
        }

    val primarySSRC: Long
        get() = rtpEncodings[0].primarySSRC

    fun getRtpLayerByQualityIdx(idx: Int): RtpLayerDesc? =
        synchronized(this) {
            layersByIndex[idx]
        }

    fun findRtpLayerDesc(videoRtpPacket: VideoRtpPacket): RtpLayerDesc? {
        synchronized(this) {
            if (ArrayUtils.isNullOrEmpty(rtpEncodings)) {
                return null
            }
            val encodingId = getEncodingId(videoRtpPacket)
            val desc = layersById[encodingId]
            if (desc != null) {
                return desc
            }
            /* ??? Does this part actually get used? */
            for (encoding in rtpEncodings) {
                if (encoding.matches(videoRtpPacket.ssrc)) {
                    return encoding.findRtpLayerDesc(videoRtpPacket)
                }
            }
            return null
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("MediaSourceDesc ").append(hashCode()).append(" has encodings:\n")
        sb.append(rtpEncodings.joinToString(separator = "\n  "))
        return sb.toString()
    }

    /**
     * FIXME: this should probably check whether the specified SSRC is part
     * of this source (i.e. check all layers and include secondary SSRCs).
     *
     * @param ssrc the SSRC to match.
     * @return `true` if the specified `ssrc` is the primary SSRC
     * for this source.
     */
    fun matches(ssrc: Long): Boolean {
        return rtpEncodings.size > 0 && rtpEncodings[0].primarySSRC == ssrc
    }
}
