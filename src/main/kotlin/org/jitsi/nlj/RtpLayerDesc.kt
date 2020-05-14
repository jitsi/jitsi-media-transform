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

import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.utils.stats.RateStatistics
import org.jitsi_modified.impl.neomedia.rtp.MediaSourceDesc

/**
 * Keeps track of its subjective quality index,
 * its last stable bitrate and other useful things for adaptivity/routing.
 *
 * @author George Politis
 */
class RtpLayerDesc(
    /**
     * The index of this instance in the source layers array.
     */
    val index: Int,
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
        primarySSRC: Long
    ) : this(0, -1 /* tid */, -1 /* sid */,
        NO_HEIGHT /* height */, NO_FRAME_RATE /* frame rate */,
        null /* dependencies */)

    /**
     * @return the "id" of this layer within this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such us the
     * rid).
     */
    val layerId: Int
        get() {
            val t = if (tid < 0) 0 else tid
            val s = if (sid < 0) 0 else sid

            return (s shl 3) or t
        }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "subjective_quality=" + index +
            ",temporal_id=" + tid +
            ",spatial_id=" + sid
    }

    fun matches(packet: VideoRtpPacket): Boolean {
        return if (tid == -1 && sid == -1) {
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
        var bitrate = rateStatistics.getRate(nowMs)

        /* TODO: does the wrong thing if we have multiple dependencies */
        if (dependencyLayers != null) {
            for (dependency in dependencyLayers) {
                bitrate += dependency.getBitrateBps(nowMs)
            }
        }

        return bitrate
    }

    /**
     * Extracts a [NodeStatsBlock] from an [RtpLayerDesc].
     */
    fun getNodeStats() = NodeStatsBlock(layerId.toString()).apply {
        addNumber("frameRate", frameRate)
        addNumber("height", height)
        addNumber("index", index)
        addNumber("bitrate_bps", getBitrateBps(System.currentTimeMillis()))
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
