package org.jitsi.nlj.rtp

import com.nhaarman.mockitokotlin2.mock
import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import java.util.logging.Level

class TransportCcEngineTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val bandwidthEstimator: BandwidthEstimator = mock()
    private val clock: FakeClock = FakeClock()
    private val logger = StdoutLogger(_level = Level.INFO)

    private val transportCcEngine = TransportCcEngine(bandwidthEstimator, logger, clock)

    init {
        "foo" {
            transportCcEngine.mediaPacketSent(4, 1300.bytes)
            val tccPacket = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 0)) {
                SetBase(1, 100)
                AddReceivedPacket(1, 100)
                AddReceivedPacket(2, 110)
                AddReceivedPacket(3, 120)
                AddReceivedPacket(4, 130)
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket, clock.instant().toEpochMilli())

            var stats = transportCcEngine.getStatistics()

            stats.numMissingPacketReports shouldBe 3
            stats.numDuplicateReports shouldBe 0
            stats.numPacketsReportedAfterLost shouldBe 0
            stats.numPacketsUnreported shouldBe 0

            transportCcEngine.mediaPacketSent(5, 1300.bytes)
            transportCcEngine.mediaPacketSent(6, 1300.bytes)

            val tccPacket2 = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 1)) {
                SetBase(4, 130)
                AddReceivedPacket(4, 130)
                AddReceivedPacket(6, 150)
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket2, clock.instant().toEpochMilli())

            stats = transportCcEngine.getStatistics()

            stats.numMissingPacketReports shouldBe 3
            stats.numDuplicateReports shouldBe 1
            stats.numPacketsReportedAfterLost shouldBe 0
            stats.numPacketsUnreported shouldBe 0

            val tccPacket3 = with(RtcpFbTccPacketBuilder(mediaSourceSsrc = 123, feedbackPacketSeqNum = 3)) {
                SetBase(5, 140)
                AddReceivedPacket(5, 140)
                AddReceivedPacket(6, 150)
                build()
            }

            transportCcEngine.rtcpPacketReceived(tccPacket3, clock.instant().toEpochMilli())

            stats = transportCcEngine.getStatistics()

            stats.numMissingPacketReports shouldBe 3
            stats.numDuplicateReports shouldBe 2
            stats.numPacketsReportedAfterLost shouldBe 1
            stats.numPacketsUnreported shouldBe 0

            transportCcEngine.mediaPacketSent(7, 1300.bytes)
            /* Force the report of sequence 7 to roll off the packet history */
            transportCcEngine.mediaPacketSent(1008, 1300.bytes)
            transportCcEngine.mediaPacketSent(1009, 1300.bytes)

            stats = transportCcEngine.getStatistics()

            stats.numMissingPacketReports shouldBe 3
            stats.numDuplicateReports shouldBe 2
            stats.numPacketsReportedAfterLost shouldBe 1
            stats.numPacketsUnreported shouldBe 1
        }
    }
}
