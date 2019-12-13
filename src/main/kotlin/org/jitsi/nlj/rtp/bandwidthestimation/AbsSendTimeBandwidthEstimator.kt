/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Duration
import java.time.Instant
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.mbps
import org.jitsi.nlj.util.observableWhenChanged
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime

private val defaultInitBw: Bandwidth = 2.5.mbps
private val defaultMinBw: Bandwidth = 30.kbps
private val defaultMaxBw: Bandwidth = 20.mbps

/**
 * Just the delay-based part of Google CC.
 */
class AbsSendTimeBandwidthEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) :
        BandwidthEstimator(diagnosticContext) {
    private val logger = parentLogger.createChildLogger(GoogleCcEstimator::class)

    override val algorithmName = "AbsSendTime Bandwidth Estimator"
    override var initBw: Bandwidth = defaultInitBw
    override var minBw: Bandwidth by observableWhenChanged(defaultMinBw) {
            _, _, newValue -> bitrateEstimatorAbsSendTime.setMinBitrate(newValue.bps.toInt())
    }
    override var maxBw: Bandwidth = (-1).bps /* not supported */

    private val bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(diagnosticContext, logger).also {
        it.setMinBitrate(minBw.bps.toInt())
    }

    override fun doProcessPacketArrival(now: Instant, sendTime: Instant?, recvTime: Instant?, seq: Int, size: DataSize, ecn: Byte) {
        if (sendTime != null && recvTime != null) {
            bitrateEstimatorAbsSendTime.incomingPacketInfo(now.toEpochMilli(),
                sendTime.toEpochMilli(), recvTime.toEpochMilli(), size.bytes.toInt())
        }
    }

    override fun doProcessPacketLoss(now: Instant, sendTime: Instant?, seq: Int) { /* no-op */ }

    override fun doRttUpdate(now: Instant, newRtt: Duration) {
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), newRtt.toMillis())
    }

    override fun getCurrentBw(now: Instant): Bandwidth {
        return bitrateEstimatorAbsSendTime.latestEstimate.bps
    }

    override fun getStats(now: Instant): StatisticsSnapshot = StatisticsSnapshot("GoogleCcEstimator", getCurrentBw(now)).apply {
        addNumber("latestEstimate", bitrateEstimatorAbsSendTime.latestEstimate)
    }

    override fun reset() {
        initBw = defaultInitBw
        minBw = defaultMinBw
        maxBw = defaultMaxBw

        bitrateEstimatorAbsSendTime.reset()
    }
}
