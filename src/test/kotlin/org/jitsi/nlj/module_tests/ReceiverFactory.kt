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

package org.jitsi.nlj.module_tests

import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpReceiver
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.test_utils.RtpExtensionInfo
import org.jitsi.test_utils.SourceAssociation
import org.jitsi.test_utils.SrtpData
import java.util.Random
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService

class ReceiverFactory {
    companion object {
        fun createReceiver(
            executor: ExecutorService,
            backgroundExecutor: ScheduledExecutorService,
            srtpData: SrtpData,
            payloadTypes: List<PayloadType>,
            headerExtensions: List<RtpExtensionInfo>,
            ssrcAssociations: List<SourceAssociation>
            ): RtpReceiver {
            val receiver = RtpReceiverImpl(
                Random().nextLong().toString(),
                {},
                null,
                RtcpEventNotifier(),
                executor,
                backgroundExecutor
            )
            receiver.setSrtpTransformer(SrtpTransformerFactory.createSrtpTransformer(srtpData))
            receiver.setSrtcpTransformer(SrtpTransformerFactory.createSrtcpTransformer(srtpData))

            payloadTypes.forEach {
                receiver.handleEvent(RtpPayloadTypeAddedEvent(it))
            }
            headerExtensions.forEach {
                receiver.handleEvent(RtpExtensionAddedEvent(it.id.toByte(), it.extension))
            }
            ssrcAssociations.forEach {
                receiver.handleEvent(SsrcAssociationEvent(it.primarySsrc, it.secondarySsrc, it.associationType))
            }

            return receiver
        }
    }
}