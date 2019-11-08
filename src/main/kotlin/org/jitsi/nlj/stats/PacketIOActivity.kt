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

package org.jitsi.nlj.stats

import org.jitsi.nlj.util.NEVER
import org.jitsi.nlj.util.latest
import java.time.Instant
import kotlin.math.max

class PacketIOActivity {
    var lastRtpPacketReceivedTimestamp: Instant = NEVER
    var lastRtpPacketSentTimestamp: Instant = NEVER

    val lastOverallRtpActivity: Instant
        get() = latest(lastRtpPacketReceivedTimestamp, lastRtpPacketSentTimestamp)

    @Deprecated(replaceWith = ReplaceWith("lastRtpPacketReceivedTimestamp"), message = "Deprecated")
    var lastPacketReceivedTimestampMs: Long = 0
    @Deprecated(replaceWith = ReplaceWith("lastRtpPacketSentTimestamp"), message = "Deprecated")
    var lastPacketSentTimestampMs: Long = 0

    @Deprecated(replaceWith = ReplaceWith("lastOverallRtpPactivity"), message = "Deprecated")
    val lastOverallActivityTimestampMs: Long
        get() {
            return max(lastPacketReceivedTimestampMs, lastPacketSentTimestampMs)
        }
}
