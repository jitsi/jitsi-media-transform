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

package org.jitsi.nlj.rtcp

import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.util.bps
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket
import java.util.concurrent.CopyOnWriteArrayList

class RembHandler : RtcpListener {
    private val bweUpdateListeners: MutableList<BandwidthEstimator.Listener> =
        CopyOnWriteArrayList()

    override fun rtcpPacketReceived(packet: RtcpPacket?, receivedTime: Long) {
        if (packet is RtcpFbRembPacket) {
            onRembPacket(packet)
        }
    }

    fun addListener(bweUpdateListener: BandwidthEstimator.Listener) {
        bweUpdateListeners.add(bweUpdateListener)
    }

    private fun onRembPacket(rembPacket: RtcpFbRembPacket) {
        bweUpdateListeners.forEach { it.bandwidthEstimationChanged(rembPacket.bitrate.bps) }
    }
}
