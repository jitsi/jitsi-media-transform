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
package org.jitsi.nlj.rtp

import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.isNewerThan

/**
 * Rewrites sequence numbers for RTP streams by hiding any gaps caused by
 * dropped packets. Rewriters are not thread-safe. If multiple threads access a
 * rewriter concurrently, it must be synchronized externally.
 *
 * Port of the class in libjitsi.
 *
 * @author Maryam Daneshi
 * @author George Politis
 * @author Boris Grozev
 */
class ResumableStreamRewriter() {
    /**
     * The sequence number delta between what's been accepted and what's been
     * received, mod 2^16.
     */
    var seqnumDelta = 0
        private set

    /**
     * The highest sequence number that got accepted, mod 2^16.
     */
    var highestSequenceNumberSent = -1
        private set

    /**
     * Rewrites the sequence number of the given RTP packet hiding any gaps caused by drops.
     *
     * @param accept true if the packet is accepted, false otherwise
     */
    fun rewriteRtp(accept: Boolean, rtpPacket: RtpPacket) {
        val sequenceNumber = rtpPacket.sequenceNumber
        val newSequenceNumber = rewriteSequenceNumber(accept, sequenceNumber)
        if (sequenceNumber != newSequenceNumber) {
            rtpPacket.sequenceNumber = newSequenceNumber
        }
    }

    /**
     * Rewrites the sequence number passed as a parameter, hiding any gaps
     * caused by drops.
     *
     * @param accept true if the packet is accepted, false otherwise
     * @param sequenceNumber the sequence number to rewrite
     * @return a rewritten sequence number that hides any gaps caused by drops.
     */
    fun rewriteSequenceNumber(accept: Boolean, sequenceNumber: Int): Int {
        if (accept) {
            // overwrite the sequence number (if needed)
            val newSequenceNumber = (sequenceNumber - seqnumDelta) and 0xffff

            // init or update the highest sent sequence number (if needed)
            if (highestSequenceNumberSent == -1 || newSequenceNumber isNewerThan highestSequenceNumberSent) {
                highestSequenceNumberSent = newSequenceNumber
            }

            return newSequenceNumber
        } else {
            // update the sequence number delta (if needed)
            if (highestSequenceNumberSent != -1) {
                val newDelta = (sequenceNumber - highestSequenceNumberSent) and 0xffff

                if (newDelta isNewerThan seqnumDelta) {
                    seqnumDelta = newDelta
                }
            }

            return sequenceNumber
        }
    }
}
