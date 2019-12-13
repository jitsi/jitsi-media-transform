package org.jitsi.nlj.transform.node.incoming

import com.nhaarman.mockitokotlin2.spy
import io.kotlintest.IsolationMode
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.format.Vp9PayloadType
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.test_utils.timeline
import org.jitsi.nlj.util.StreamInformationStoreImpl
import org.jitsi.nlj.util.ms
import org.jitsi.nlj.util.times
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension
import java.time.Duration

class RemoteBandwidthEstimatorTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val clock: FakeClock = spy()
    private val astExtensionId = 3
    // REMB is enabled by having at least one payload type which has "transport-cc" signaled as a rtcp-fb, and TCC is
    // disabled.
    private val vp8PayloadType = Vp8PayloadType(100, emptyMap(), setOf("goog-remb"))
    private val vp9PayloadTypeWithTcc = Vp9PayloadType(101, emptyMap(), setOf("transport-cc"))
    private val packetInterval = 10.ms()
    private val ssrc = 1234L
    private val streamInformationStore = StreamInformationStoreImpl().apply {
        addRtpExtensionMapping(RtpExtension(astExtensionId.toByte(), RtpExtensionType.ABS_SEND_TIME))
        addRtpPayloadType(vp8PayloadType)
    }

    private val remoteBandwidthEstimator = RemoteBandwidthEstimator(streamInformationStore, StdoutLogger(), clock = clock)

    init {
        "when REMB is not signaled" {
            streamInformationStore.clearRtpPayloadTypes()
            sendPackets()
            "no feedback should be produced" {
                remoteBandwidthEstimator.createRemb() shouldBe null
            }
        }
        "when both REMB and TCC are signaled" {
            streamInformationStore.addRtpPayloadType(vp9PayloadTypeWithTcc)
            sendPackets()
            "no feedback should be produced" {
                remoteBandwidthEstimator.createRemb() shouldBe null
            }
        }
        "when REMB is signaled" {
            sendPackets()
            val rembPacket = remoteBandwidthEstimator.createRemb()
            "a feedback packet should be produced" {
                rembPacket shouldNotBe null
                "with valid bitrate" {
                    rembPacket!!.bitrate shouldBeGreaterThan 0
                }
                "with the correct SSRCs" {
                    rembPacket!!.ssrcs shouldBe listOf(ssrc)
                }
            }
        }
    }

    private fun createPacket(duration: Duration): PacketInfo {
        val dummyPacket = RtpPacket(ByteArray(1500), 0, 1500).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = 100
            sequenceNumber = 123
            timestamp = 456L
            ssrc = this@RemoteBandwidthEstimatorTest.ssrc
            length = 666
        }
        val ext = dummyPacket.addHeaderExtension(astExtensionId, AbsSendTimeHeaderExtension.DATA_SIZE_BYTES)
        AbsSendTimeHeaderExtension.setTime(ext, duration.toNanos())

        return PacketInfo(dummyPacket).apply { receivedTime = duration.toMillis() }
    }

    /**
     * Sends 1000 packets spaced at [packetInterval] with no jitter.
     */
    private fun sendPackets() {
        timeline(clock) {
            repeat(1000) {
                run { remoteBandwidthEstimator.processPacket(createPacket(packetInterval * it)) }
                elapse(packetInterval)
            }
        }.run()
    }
}
