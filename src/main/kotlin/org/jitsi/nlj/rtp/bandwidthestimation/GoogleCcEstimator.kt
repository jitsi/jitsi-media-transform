package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Duration
import java.time.Instant
import kotlin.properties.Delegates
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.mbps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi_modified.impl.neomedia.rtp.remotebitrateestimator.RemoteBitrateEstimatorAbsSendTime
import org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation

private val defaultInitBw: Bandwidth = 2.5.mbps
private val defaultMinBw: Bandwidth = 30.kbps
private val defaultMaxBw: Bandwidth = 20.mbps

class GoogleCcEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) : BandwidthEstimator {
    override val algorithmName = "Google CC"

    /* TODO: Use configuration service to set this default value. */
    override var initBw: Bandwidth = defaultInitBw
    /* TODO: observable which sets the components' values if we're in initial state. */

    override var minBw: Bandwidth by Delegates.observable(defaultMinBw) {
        _, _, newValue ->
        bitrateEstimatorAbsSendTime.setMinBitrate(newValue.bps.toInt())
        sendSideBandwidthEstimation.setMinMaxBitrate(newValue.bps.toInt(), maxBw.bps.toInt())
    }

    override var maxBw: Bandwidth by Delegates.observable(defaultMaxBw) {
        _, _, newValue ->
        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), newValue.bps.toInt())
    }

    private val logger = parentLogger.createChildLogger(GoogleCcEstimator::class)

    /**
     * Implements the delay-based part of Google CC.
     */
    private val bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(null, diagnosticContext, logger)
    init {
        bitrateEstimatorAbsSendTime.setMinBitrate(minBw.bps.toInt())
    }

    /**
     * Implements the loss-based part of Google CC.
     */
    private val sendSideBandwidthEstimation = SendSideBandwidthEstimation(diagnosticContext, initBw.bps.toLong(), logger)
    init {
        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
    }

    override fun processPacketArrival(now: Instant, sendTime: Instant?, recvTime: Instant?, seq: Int, size: DataSize, ecn: Byte) {
        if (sendTime != null && recvTime != null) {
            /* TODO: update bitrateEstimatorAbsSendTime to do all math in millis. */
            val sendTime24bits = RemoteBitrateEstimatorAbsSendTime
                    .convertMsTo24Bits(sendTime.toEpochMilli())

            bitrateEstimatorAbsSendTime.incomingPacketInfo(now.toEpochMilli(),
                    recvTime.toEpochMilli(), sendTime24bits, size.bytes.toInt(), 1 /* TODO */)
        }
        sendSideBandwidthEstimation.updateReceiverEstimate(bitrateEstimatorAbsSendTime.latestEstimate)
        sendSideBandwidthEstimation.updateReceiverBlock(0, 1, now.toEpochMilli())

        /* TODO: rate-limit how often we call updateEstimate? */
        sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
    }

    override fun processPacketLoss(now: Instant, sendTime: Instant?, seq: Int) {
        sendSideBandwidthEstimation.updateReceiverBlock(255, 1, now.toEpochMilli())
        sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
    }

    override fun onRttUpdate(now: Instant, newRtt: Duration) {
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), newRtt.toMillis(), -1)
        sendSideBandwidthEstimation.onRttUpdate(newRtt.toNanos() / 1.0e9)
    }

    override fun getCurrentBw(now: Instant): Bandwidth {
        return sendSideBandwidthEstimation.latestEstimate.bps
    }

    override fun getStats(): NodeStatsBlock {
        TODO("not implemented")
    }

    override fun reset() {
        initBw = defaultInitBw
        minBw = defaultMinBw
        maxBw = defaultMaxBw

        bitrateEstimatorAbsSendTime.reset()
        sendSideBandwidthEstimation.reset(initBw.bps.toLong())

        sendSideBandwidthEstimation.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
    }
}
