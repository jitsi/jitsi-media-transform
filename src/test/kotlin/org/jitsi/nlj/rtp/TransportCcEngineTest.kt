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

package org.jitsi.nlj.rtp

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.spy
import io.kotlintest.IsolationMode
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.shouldBe
import org.jitsi.nlj.resources.logging.StdoutLogger
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimator
import org.jitsi.nlj.test_utils.FakeClock
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import java.util.logging.Level

class TransportCcEngineTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val bandwidthEstimator: BandwidthEstimator = mock()
    private val clock: FakeClock = spy()
    private val logger = StdoutLogger(_level = Level.INFO)

    private val transportCcEngine = TransportCcEngine(bandwidthEstimator, logger, clock)

    init {
        "sdads" {
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
        }
    }
}