package org.jitsi.nlj.rtp.bandwidthestimation

import java.time.Duration
import java.time.Instant
import kotlin.properties.Delegates
import org.jitsi.nlj.util.Bandwidth
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

class GoogleCcEstimator(diagnosticContext: DiagnosticContext, parentLogger: Logger) :
    BandwidthEstimator(diagnosticContext) {
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
    private val bitrateEstimatorAbsSendTime = RemoteBitrateEstimatorAbsSendTime(diagnosticContext, logger).also {
        it.setMinBitrate(minBw.bps.toInt())
    }

    /**
     * Implements the loss-based part of Google CC.
     */
    private val sendSideBandwidthEstimation = SendSideBandwidthEstimation(diagnosticContext, initBw.bps.toLong(), logger).also {
        it.setMinMaxBitrate(minBw.bps.toInt(), maxBw.bps.toInt())
    }

    override fun doProcessPacketTransmission(now: Instant, stats: PacketStats) {
        /* Do nothing, Google CC doesn't care about packet transmission. */
    }

    override fun doProcessPacketArrival(now: Instant, stats: PacketStats, recvTime: Instant?, recvEcn: Byte) {
        if (stats.sendTime != null && recvTime != null) {
            bitrateEstimatorAbsSendTime.incomingPacketInfo(now.toEpochMilli(),
                    recvTime.toEpochMilli(), stats.sendTime.toEpochMilli(), stats.size.bytes.toInt())
        }
        sendSideBandwidthEstimation.updateReceiverEstimate(bitrateEstimatorAbsSendTime.latestEstimate)
        sendSideBandwidthEstimation.reportPacketArrived(now.toEpochMilli())
    }

    override fun doProcessPacketLoss(now: Instant, stats: PacketStats) {
        sendSideBandwidthEstimation.reportPacketLost(now.toEpochMilli())
    }

    override fun feedbackComplete(now: Instant) {
        /* TODO: rate-limit how often we call updateEstimate? */
        sendSideBandwidthEstimation.updateEstimate(now.toEpochMilli())
        reportBandwidthEstimate(now, sendSideBandwidthEstimation.latestEstimate.bps)
    }

    override fun doRttUpdate(now: Instant, newRtt: Duration) {
        bitrateEstimatorAbsSendTime.onRttUpdate(now.toEpochMilli(), newRtt.toMillis())
        sendSideBandwidthEstimation.onRttUpdate(newRtt)
    }

    override fun getCurrentBw(now: Instant): Bandwidth {
        return sendSideBandwidthEstimation.latestEstimate.bps
    }

    override fun getStats(now: Instant): StatisticsSnapshot = StatisticsSnapshot("GoogleCcEstimator", getCurrentBw(now)).apply {
        addNumber("latestDelayEstimate", sendSideBandwidthEstimation.latestREMB)
        addNumber("latestFractionLoss", sendSideBandwidthEstimation.latestFractionLoss)
        with(sendSideBandwidthEstimation.statistics) {
            update(now.toEpochMilli())
            addNumber("lossDegradedMs", lossDegradedMs)
            addNumber("lossFreeMs", lossFreeMs)
            addNumber("lossLimitedMs", lossLimitedMs)
        }
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
