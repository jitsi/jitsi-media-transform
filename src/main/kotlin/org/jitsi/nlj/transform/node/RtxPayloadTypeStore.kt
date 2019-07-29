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

package org.jitsi.nlj.transform.node

import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.observable.map.MapEventHandler
import org.jitsi.nlj.util.observable.map.MapEventValueFilter
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.utils.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Filters RtpPayloadTypeEvents from a [StreamInformationStore] to hold only
 * information about RTX payload types and their associated video
 * payload types
 */
class RtxPayloadTypeStore(
    private val logger: Logger
) {
    /**
     * Maps the RTX payload types to their associated video payload types
     */
    private val _associatedPayloadTypes: MutableMap<Int, Int> = ConcurrentHashMap()
    val associatedPayloadTypes: Map<Int, Int> = _associatedPayloadTypes

    private val mapEventFilter = object : MapEventValueFilter<Byte, PayloadType>({ it is RtxPayloadType }) {
        override fun entryAdded(key: Byte, value: PayloadType) =
            setRtxPayloadTypeAssociation(value as RtxPayloadType)

        override fun entryUpdated(key: Byte, newValue: PayloadType) =
            setRtxPayloadTypeAssociation(newValue as RtxPayloadType)

        override fun entryRemoved(key: Byte) {
            _associatedPayloadTypes.remove(key.toPositiveInt())
        }
    }

    val mapEventHandler: MapEventHandler<Byte, PayloadType> = mapEventFilter.mapEventHandler

    private fun setRtxPayloadTypeAssociation(rtxPayloadType: RtxPayloadType) {
        rtxPayloadType.associatedPayloadType?.let { associatedPayloadType ->
            _associatedPayloadTypes[rtxPayloadType.pt.toPositiveInt()] = associatedPayloadType
            logger.cdebug { "Associating RTX payload type ${rtxPayloadType.pt.toPositiveInt()} " +
                "with primary $associatedPayloadType" }
        } ?: run {
            logger.cerror { "Unable to parse RTX associated payload type from payload " +
                "type $rtxPayloadType" }
        }
    }

    fun isRtx(ptId: Int): Boolean =
        _associatedPayloadTypes.containsKey(ptId)

    fun getOriginalPayloadType(ptId: Int): Int? {
        return _associatedPayloadTypes.entries.asSequence()
            .filter { it.value == ptId }
            .map { it.key }
            .firstOrNull()
    }
}
