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

import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.utils.ArrayUtils
import org.jitsi.utils.stats.RateStatistics
import org.jitsi_modified.impl.neomedia.rtp.MediaSourceDesc

/**
 * Keeps track of how many channels receive it, its subjective quality index,
 * its last stable bitrate and other useful things for adaptivity/routing.
 *
 * @author George Politis
 */
class RtpLayerDesc(
    /**
     * The [MediaSourceDesc] that this [RtpLayerDesc]
     * belongs to.
     */
    private val source: MediaSourceDesc,
    /**
     * The index of this instance in the source layers array.
     */
    val index: Int,
    /**
     * The primary SSRC for this encoding.
     */
    val primarySSRC: Long,
    /**
     * The temporal layer ID of this instance.
     */
    val tid: Int,
    /**
     * The spatial layer ID of this instance.
     */
    val sid: Int,
    /**
     * The max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.
     */
    // XXX we should be able to sniff the actual height from the RTP
    // packets.
    val height: Int,
    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.
     */
    val frameRate: Double,
    /**
     * The [RtpLayerDesc] on which this layer depends.
     */
    private val dependencyLayers: Array<RtpLayerDesc>?
) {
    /**
     * The ssrcs associated with this encoding (for example, RTX or FLEXFEC)
     * Maps ssrc -> type [SsrcAssociationType] (rtx, etc.)
     */
    private val secondarySsrcs: MutableMap<Long, SsrcAssociationType> = HashMap()

    /**
     * The root [RtpLayerDesc] of the dependencies DAG. Useful for
     * simulcast handling.
     */
    var baseLayer: RtpLayerDesc =
        if (dependencyLayers == null || dependencyLayers.isEmpty())
            this
        else
            dependencyLayers[0].baseLayer

    /**
     * The [RateStatistics] instance used to calculate the receiving
     * bitrate of this RTP layer.
     */
    private val rateStatistics = RateStatistics(AVERAGE_BITRATE_WINDOW_MS)

    /**
     * Ctor.
     *
     * @param source the [MediaSourceDesc] that this instance
     * belongs to.
     * @param primarySSRC The primary SSRC for this layering/encoding.
     */
    constructor(
        source: MediaSourceDesc,
        primarySSRC: Long
    ) : this(source, 0, primarySSRC, -1 /* tid */, -1 /* sid */,
        NO_HEIGHT /* height */, NO_FRAME_RATE /* frame rate */,
        null /* dependencies */)

    /**
     * @return the "id" of this layer/encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such us the
     * rid). This server-side id is used in the layer lookup table that is
     * maintained in [MediaSourceDesc].
     */
    val encodingId: Long
        get() {
            var encodingId = primarySSRC
            if (tid > -1) {
                encodingId = encodingId or (tid.toLong() shl 32)
            }
            return encodingId
        }

    fun addSecondarySsrc(ssrc: Long, type: SsrcAssociationType) {
        secondarySsrcs[ssrc] = type
    }

    /**
     * Get the secondary ssrc for this encoding that corresponds to the given
     * type
     * @param type the type of the secondary ssrc (e.g. RTX)
     * @return the ssrc for the encoding that corresponds to the given type,
     * if it exists; otherwise -1
     */
    fun getSecondarySsrc(type: SsrcAssociationType): Long {
        for ((key, value) in secondarySsrcs) {
            if (value == type) {
                return key
            }
        }
        return -1
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "subjective_quality=" + index +
            ",primary_ssrc=" + primarySSRC +
            ",secondary_ssrcs=" + secondarySsrcs +
            ",temporal_id=" + tid +
            ",spatial_id=" + sid
    }

    fun matches(packet: VideoRtpPacket): Boolean {
        return if (!matches(packet.ssrc)) {
            false
        } else if (tid == -1 && sid == -1) {
            true
        } else if (packet is Vp8Packet) {
            // NOTE(brian): the spatial layer index of an encoding is only currently used for in-band spatial
            // scalability (a la vp9), so it isn't used for anything we're currently supporting (and is
            // codec-specific, so should probably be implemented in another way anyhow) so for now we don't
            // check that here (note, though, that the spatial layer index in a packet is currently set as of
            // the time of this writing and is from the perspective of a logical spatial index, i.e. the lowest sim
            // stream (180p) has spatial index 0, 360p has 1, 720p has 2.
            val vp8PacketTid = packet.temporalLayerIndex
            tid == vp8PacketTid || vp8PacketTid == -1 && tid == 0
        } else {
            true
        }
    }

    /**
     * Gets a boolean indicating whether or not the SSRC specified in the
     * arguments matches this encoding or not.
     *
     * @param ssrc the SSRC to match.
     */
    fun matches(ssrc: Long): Boolean {
        return if (primarySSRC == ssrc) {
            true
        } else secondarySsrcs.containsKey(ssrc)
    }

    /**
     *
     * @param packetSizeBytes
     * @param nowMs
     */
    fun updateBitrate(packetSizeBytes: Int, nowMs: Long) {
        // Update rate stats (this should run after padding termination).
        rateStatistics.update(packetSizeBytes, nowMs)
    }

    /**
     * Gets the cumulative bitrate (in bps) of this [RtpLayerDesc] and
     * its dependencies.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this [RtpLayerDesc]
     * and its dependencies.
     */
    fun getBitrateBps(nowMs: Long): Long {
        val layers = source.rtpLayers
        if (ArrayUtils.isNullOrEmpty(layers)) {
            return 0
        }
        val rates = LongArray(layers.size)
        getBitrateBps(nowMs, rates)
        var bitrate: Long = 0
        for (i in rates.indices) {
            bitrate += rates[i]
        }
        return bitrate
    }

    /**
     * Recursively adds the bitrate (in bps) of this [RtpLayerDesc] and
     * its dependencies in the array passed in as an argument.
     *
     * @param nowMs
     */
    private fun getBitrateBps(nowMs: Long, rates: LongArray) {
        if (rates[index] == 0L) {
            rates[index] = rateStatistics.getRate(nowMs)
        }
        if (!ArrayUtils.isNullOrEmpty(dependencyLayers)) {
            for (dependency in dependencyLayers!!) {
                dependency.getBitrateBps(nowMs, rates)
            }
        }
    }

    /**
     * Extracts a [NodeStatsBlock] from an [RtpLayerDesc].
     */
    fun getNodeStats() = NodeStatsBlock(primarySSRC.toString()).apply {
        addNumber("frameRate", frameRate)
        addNumber("height", height)
        addNumber("index", index)
        addNumber("bitrate_bps", getBitrateBps(System.currentTimeMillis()))
        addNumber("rtx_ssrc", getSecondarySsrc(SsrcAssociationType.RTX))
        addNumber("fec_ssrc", getSecondarySsrc(SsrcAssociationType.FEC))
        addNumber("tid", tid)
        addNumber("sid", sid)
    }

    companion object {
        /**
         * The quality that is used to represent that forwarding is suspended.
         */
        const val SUSPENDED_INDEX = -1

        /**
         * A value used to designate the absence of height information.
         */
        private const val NO_HEIGHT = -1

        /**
         * A value used to designate the absence of frame rate information.
         */
        private const val NO_FRAME_RATE = -1.0

        /**
         * The default window size in ms for the bitrate estimation.
         *
         * TODO maybe make this configurable.
         */
        private const val AVERAGE_BITRATE_WINDOW_MS = 5000

        /**
         * @param videoRtpPacket the video packet
         * @return gets the server-side layer/encoding id (see
         * [.getEncodingId]) of a video packet.
         */
        @JvmStatic
        fun getEncodingId(videoRtpPacket: VideoRtpPacket): Long {
            var encodingId = videoRtpPacket.ssrc
            if (videoRtpPacket is Vp8Packet) {
                // note(george) we've observed that a client may announce but not
                // send simulcast (it is not clear atm who's to blame for this
                // "bug", chrome or our client code). In any case, when this happens
                // we "pretend" that the encoding of the packet is the base temporal
                // layer of the rtp stream (ssrc) of the packet.
                var tid = videoRtpPacket.temporalLayerIndex
                if (tid < 0) {
                    tid = 0
                }
                encodingId = encodingId or (tid.toLong() shl 32)
            }
            return encodingId
        }
    }
}
