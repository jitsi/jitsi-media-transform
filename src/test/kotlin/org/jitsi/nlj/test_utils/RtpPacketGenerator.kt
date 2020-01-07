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
package org.jitsi.nlj.test_utils

import com.nhaarman.mockitokotlin2.spy
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.rtp.rtp.RtpPacket
import java.time.Duration

class RtpPacketGenerator internal constructor(
    /**
     * Length in bytes of each packet.
     */
    val lengthBytes: Int,
    /**
     * The number of packets to generate.
     */
    val count: Int,
    /**
     * The interval to advance the clock after each packet.
     */
    val interval: Duration,
    val clock: FakeClock = spy()
) {

    internal constructor(
        /**
         * The bitrate to generate packets at.
         */
        targetBitrate: Bandwidth,
        /**
         * Length in bytes of each packet.
         */
        lengthBytes: Int,
        /**
         * The total duration over which packets will be generated.
         */
        duration: Duration = Duration.ofSeconds(10),
        clock: FakeClock = spy()
    ) : this(
        lengthBytes = lengthBytes,
        count = getCount(duration, getInterval(targetBitrate, lengthBytes)),
        interval = getInterval(targetBitrate, lengthBytes),
        clock = clock
    )

    internal constructor(
        /**
         * The bitrate to generate packets at.
         */
        targetBitrate: Bandwidth,
        /**
         * The interval to advance the clock after each packet.
         */
        interval: Duration = Duration.ofMillis(10),
        /**
         * The total duration over which packets will be generated.
         */
        duration: Duration = Duration.ofSeconds(10),
        clock: FakeClock = spy()
    ) : this(
        lengthBytes = getLengthBytes(targetBitrate, interval),
        count = getCount(duration, interval),
        interval = interval,
        clock = clock
    )

    companion object {
        const val bpsNanosToBytes = 1 / 8e9
        private fun getInterval(targetBitrate: Bandwidth, lengthBytes: Int): Duration {
            val seconds = 8 * lengthBytes.toDouble() / targetBitrate.bps
            return Duration.ofMillis((seconds * 1000).toLong())
        }
        private fun getLengthBytes(targetBitrate: Bandwidth, interval: Duration): Int =
            (targetBitrate.bps * interval.toNanos() * bpsNanosToBytes).toInt()
        private fun getCount(duration: Duration, interval: Duration): Int =
            (duration.toMillis().toDouble() / interval.toMillis()).toInt()
    }

    fun createPacket(seq: Int, len: Int, packetSsrc: Long, pt: Int, receivedTime: Long): PacketInfo {
        val dummyPacket = RtpPacket(ByteArray(len), 0, len).apply {
            version = 2
            hasPadding = false
            hasExtensions = false
            isMarked = false
            payloadType = pt
            sequenceNumber = seq
            timestamp = 456L
            ssrc = packetSsrc
        }
        return PacketInfo(dummyPacket).apply { this.receivedTime = receivedTime }
    }

    /**
     * @param startSeq the sequence number of the first packet. Subsequent packets' sequence numbers increment.
     * @param ssrc the SSRC.
     * @param payloadType the payload type number.
     * @param processPacket the function to invoke for each packet.
     */
    fun generatePackets(
        startSeq: Int = 0,
        ssrc: Long = 0,
        payloadType: Int = 100,
        processPacket: (PacketInfo) -> Unit
    ) {
        timeline(clock) {
            repeat(count) {
                run {
                    processPacket(createPacket(
                        seq = startSeq + it,
                        len = lengthBytes,
                        packetSsrc = ssrc,
                        pt = payloadType,
                        receivedTime = clock.millis()))
                }
                elapse(interval)
            }
        }.run()
    }
}
