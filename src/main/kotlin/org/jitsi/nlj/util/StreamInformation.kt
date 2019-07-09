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

package org.jitsi.nlj.util

import org.jitsi.nlj.Event
import org.jitsi.nlj.EventHandler
import org.jitsi.nlj.ReceiveSsrcAddedEvent
import org.jitsi.nlj.ReceiveSsrcRemovedEvent
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType

/**
 * A handler installed on a specific [RtpExtensionType] to be notified
 * when that type is mapped to an id.  A null id value indicates the
 * extension mapping has been removed
 */
typealias RtpExtensionHandler = (Int?) -> Unit

/**
 * [StreamInformation] manages various signalled information
 * about streams on the scope of a [Transceiver].
 *
 */
class StreamInformation : EventHandler {
    val receiveSsrcs = mutableSetOf<Long>()
    private val extensionsLock = Any()
    private val extensionHandlers =
        mutableMapOf<RtpExtensionType, MutableList<RtpExtensionHandler>>()
    private val rtpExtensions = mutableListOf<RtpExtension>()

    /**
     * Video SSRCs received by this [Transceiver] which
     * are for primary video, i.e. not FEC or RTX
     * SSRCs
     */
    val receiveVideoSsrcs = mutableSetOf<Long>()

    override fun handleEvent(event: Event) {
        when (event) {
            is ReceiveSsrcAddedEvent -> receiveSsrcs.add(event.ssrc)
            is ReceiveSsrcRemovedEvent -> receiveSsrcs.remove(event.ssrc)
            is RtpExtensionAddedEvent -> {
                synchronized(extensionsLock) {
                    rtpExtensions.add(event.rtpExtension)
                    extensionHandlers.get(event.rtpExtension.type)?.forEach { it(event.rtpExtension.id.toInt()) }
                }
            }
            is RtpExtensionClearEvent -> {
                synchronized(extensionsLock) {
                    rtpExtensions.clear()
                    extensionHandlers.values.forEach { handlers -> handlers.forEach { it(null) } }
                }
            }
        }
    }

    fun onExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {
        synchronized(extensionsLock) {
            extensionHandlers.getOrPut(rtpExtensionType, { mutableListOf() }).add(handler)
            rtpExtensions.find { it.type == rtpExtensionType }?.let { handler(it.id.toInt()) }
        }
    }
}