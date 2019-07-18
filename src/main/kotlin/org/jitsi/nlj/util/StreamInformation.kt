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

import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.supportsPli
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.util.observable.map.MapEventHandler
import org.jitsi.nlj.util.observable.map.ObservableMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A handler installed on a specific [RtpExtensionType] to be notified
 * when that type is mapped to an id.  A null id value indicates the
 * extension mapping has been removed
 */
typealias RtpExtensionHandler = (Int?) -> Unit

typealias RtpPayloadTypeEventHandler = MapEventHandler<Byte, PayloadType>

interface ReadOnlyStreamInformationStore {
    val rtpExtensions: List<RtpExtension>
        get() = emptyList()
    fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {}

    val rtpPayloadTypes: Map<Byte, PayloadType>
        get() = emptyMap()
    fun onRtpPayloadTypeEvent(handler: RtpPayloadTypeEventHandler) {}

    val supportsRtx: Boolean
        get() = false

    val supportsPli: Boolean
        get() = false
}

/**
 * [StreamInformationStore] maintains various information about streams, including:
 * 1) RTP Extension mapping
 *
 * and allows classes to register to be notified of certain events/mappings
 */
interface StreamInformationStore : ReadOnlyStreamInformationStore {
    fun addRtpExtensionMapping(rtpExtension: RtpExtension)
    fun clearRtpExtensions()

    fun addRtpPayloadType(payloadType: PayloadType)
    fun clearRtpPayloadTypes()
}

class StreamInformationStoreImpl(val id: String) : StreamInformationStore {
    private val logger = getLogger(this.javaClass)

    private val extensionsLock = Any()
    private val extensionHandlers =
        mutableMapOf<RtpExtensionType, MutableList<RtpExtensionHandler>>()
    private val _rtpExtensions: MutableList<RtpExtension> = CopyOnWriteArrayList()
    override val rtpExtensions: List<RtpExtension>
        get() = _rtpExtensions

    private val _rtpPayloadTypes = ObservableMap<Byte, PayloadType>()
    override val rtpPayloadTypes: Map<Byte, PayloadType>
        get() = _rtpPayloadTypes

    private var _supportsRtx: Boolean = false
    override val supportsRtx: Boolean
        get() = _supportsRtx

    private var _supportsPli: Boolean = false
    override val supportsPli: Boolean
        get() = _supportsPli

    override fun addRtpExtensionMapping(rtpExtension: RtpExtension) {
        synchronized(extensionsLock) {
            _rtpExtensions.add(rtpExtension)
            extensionHandlers.get(rtpExtension.type)?.forEach { it(rtpExtension.id.toInt()) }
        }
    }

    override fun clearRtpExtensions() {
        synchronized(extensionsLock) {
            _rtpExtensions.clear()
            extensionHandlers.values.forEach { handlers -> handlers.forEach { it(null) } }
        }
    }

    override fun onRtpExtensionMapping(rtpExtensionType: RtpExtensionType, handler: RtpExtensionHandler) {
        synchronized(extensionsLock) {
            extensionHandlers.getOrPut(rtpExtensionType, { mutableListOf() }).add(handler)
            _rtpExtensions.find { it.type == rtpExtensionType }?.let { handler(it.id.toInt()) }
        }
    }

    override fun addRtpPayloadType(payloadType: PayloadType) {
        _rtpPayloadTypes[payloadType.pt] = payloadType
        if (payloadType is RtxPayloadType) {
            if (!_supportsRtx) {
                logger.cdebug { "$id RTX payload type signaled, enabling RTX probing" }
            }
            _supportsRtx = true
        }
        if (payloadType.rtcpFeedbackSet.supportsPli()) {
            if (!_supportsPli) {
                logger.cdebug { "$id PLI support signaled for payload type $payloadType" }
            }
        }
    }

    override fun clearRtpPayloadTypes() {
        _rtpPayloadTypes.clear()
        _supportsRtx = false
        _supportsPli = false
        logger.cdebug { "$id RTX payload type removed, disabling RTX probing" }
    }

    override fun onRtpPayloadTypeEvent(handler: RtpPayloadTypeEventHandler) =
        _rtpPayloadTypes.onChange(handler)
}