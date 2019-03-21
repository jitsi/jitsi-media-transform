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
package org.jitsi.nlj

import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.utils.MediaType
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc

interface Event

class RtpPayloadTypeAddedEvent(val payloadType: PayloadType) : Event {
    override fun toString(): String = with(StringBuffer()) {
        append(payloadType.toString())
        toString()
    }
}
class RtpPayloadTypeClearEvent : Event

class RtpExtensionAddedEvent(val rtpExtension: RtpExtension) : Event
class RtpExtensionClearEvent : Event

class ReceiveSsrcAddedEvent(val ssrc: Long) : Event
class ReceiveSsrcRemovedEvent(val ssrc: Long) : Event

class SsrcAssociationEvent(
    val primarySsrc: Long,
    val secondarySsrc: Long,
    val type: SsrcAssociationType
) : Event

class SetMediaStreamTracksEvent(val mediaStreamTrackDescs: Array<MediaStreamTrackDesc>) : Event

class SetLocalSsrcEvent(val mediaType: MediaType, val ssrc: Long) : Event