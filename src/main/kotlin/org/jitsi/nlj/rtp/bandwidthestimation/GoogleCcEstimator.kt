package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Instant
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation

class GoogleCcEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) : BandwidthEstimator {
    override val algorithmName = "Google CC"

    /* TODO: Use configuration service to set this default value. */
    override var initBw: Float = 2_500_000.0f

    override var minBw: Float = 30_000.0f

    override var maxBw: Float = 20_000_000.0f

    private val logger = parentLogger.createChildLogger(GoogleCcEstimator::class)

    /**
     * Implements the delay-based part of Google CC.
     */
    private var bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(null, diagnosticContext, logger)

    /**
     * Implements the loss-based part of Google CC.
     */
    private var sendSideBandwidthEstimation = SendSideBandwidthEstimation(diagnosticContext, initBw.toLong(), logger)
    init {
        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.toInt(), maxBw.toInt())
    }

    override fun processPacketArrival(now: Instant, sendTime: Instant?, recvTime: Instant?, seq: Int, size: Int, ecn: Byte) {
        TODO("not implemented")
    }

    override fun processPacketLoss(now: Instant, sendTime: Instant?, seq: Int) {
        TODO("not implemented")
    }

    override fun getCurrentBw(now: Instant): Float {
        TODO("not implemented")
    }

    override fun getStats(): NodeStatsBlock {
        TODO("not implemented")
    }

    override fun reset() {
        TODO("not implemented")
    }
}
