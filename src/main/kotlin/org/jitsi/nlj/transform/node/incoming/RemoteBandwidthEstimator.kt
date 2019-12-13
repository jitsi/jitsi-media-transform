/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtensionType.ABS_SEND_TIME
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.nlj.util.observableWhenChanged
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacketBuilder
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension
import org.jitsi.utils.LRUCache
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import java.time.Clock
import java.util.Collections

/**
 * Estimates the available bandwidth for the incoming stream using the abs-send-time extension.
 */
class RemoteBandwidthEstimator(
    streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    diagnosticContext: DiagnosticContext = DiagnosticContext(),
    private val clock: Clock = Clock.systemUTC()
) : ObserverNode("Remote Bandwidth Estimator") {
    private val logger = parentLogger.createChildLogger(RemoteBandwidthEstimator::class)
    /**
     * The remote bandwidth estimation is enabled when REMB support is signaled, but TCC is not signaled.
     */
    private var enabled: Boolean by observableWhenChanged(false) {
        _, _, newValue -> logger.debug { "Setting enabled=$newValue." }
    }
    private var astExtId: Int? = null
    private var rbe: RemoteBitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(diagnosticContext, logger)
    private val ssrcs = Collections.newSetFromMap(LRUCache<Long, Boolean>(MAX_SSRCS, true /* accessOrder */))
    private var numRembsCreated = 0
    private var numPacketsWithoutAbsSendTime = 0

    init {
        streamInformationStore.onRtpExtensionMapping(ABS_SEND_TIME) {
            astExtId = it
            logger.debug { "Setting abs-send-time extension ID to $astExtId" }
        }
        streamInformationStore.onRtpPayloadTypesChanged {
            enabled = streamInformationStore.supportsRemb && !streamInformationStore.supportsTcc
        }
    }

    companion object {
        private const val MAX_SSRCS: Int = 8
    }

    override fun observe(packetInfo: PacketInfo) {
        if (!enabled) return

        astExtId?.let {
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            rtpPacket.getHeaderExtension(it)?.let { ext ->
                val sendTimeAbsSendTime = AbsSendTimeHeaderExtension.getTime(ext).toLong()
                rbe.incomingPacketInfoAbsSendTime(
                    clock.millis(),
                    sendTimeAbsSendTime,
                    packetInfo.receivedTime,
                    rtpPacket.length)
                ssrcs.add(rtpPacket.ssrc)
            }
        } ?: numPacketsWithoutAbsSendTime++
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addString("ast_ext_id", astExtId.toString())
        addBoolean("enabled", enabled)
        addNumber("num_rembs_created", numRembsCreated)
        addNumber("num_packets_without_ast", numPacketsWithoutAbsSendTime)
    }

    fun createRemb(): RtcpFbRembPacket? {
        // REMB based BWE is not configured.
        if (!enabled || astExtId == null) return null

        // The estimator does not yet have a valid value.
        if (rbe.latestEstimate == -1L) return null

        numRembsCreated++
        return RtcpFbRembPacketBuilder(brBps = rbe.latestEstimate, ssrcs = ssrcs.toList()).build()
    }
}
