package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Instant
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation

class GoogleCcEstimator : BandwidthEstimator {

    /**
     * Implements the delay-based part of Google CC.
     */
    private lateinit var bitrateEstimatorAbsSendTime: RemoteBitrateEstimatorAbsSendTime

    /**
     * Implements the loss-based part of Google CC.
     */
    private lateinit var sendSideBandwidthEstimation: SendSideBandwidthEstimation

    override val algorithmName = "Google CC"

    /* TODO: Use configuration service to set this default value. */
    override var initBw: Float = 2_500_000.0f

    override var minBw: Float = 30_000.0f

    override var maxBw: Float = 20_000_000.0f

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
