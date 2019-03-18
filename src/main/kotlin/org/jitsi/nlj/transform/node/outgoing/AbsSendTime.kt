/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AbsSendTimeHeaderExtension
import org.jitsi.service.neomedia.RTPExtension
import unsigned.toUInt

class AbsSendTime : TransformerNode("Absolute send time") {
    private var extensionId: Int = -1

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val absSendTimeExt = AbsSendTimeHeaderExtension(extensionId, System.nanoTime());
        packetInfo.packetAs<RtpPacket>().header.addExtension(extensionId, absSendTimeExt)
        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (RTPExtension.ABS_SEND_TIME_URN.equals(event.rtpExtension.uri.toString())) {
                    extensionId = event.extensionId.toUInt()
                    logger.cdebug { "Setting extension ID to $extensionId" }
                }
            }
            is RtpExtensionClearEvent -> {
                extensionId = -1
            }
        }
    }
}
