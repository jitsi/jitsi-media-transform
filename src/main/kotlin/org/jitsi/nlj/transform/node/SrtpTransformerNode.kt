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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.srtp.AbstractSrtpTransformer
import org.jitsi.nlj.stats.NodeStatsBlock

class SrtpTransformerNode(name: String) : MultipleOutputTransformerNode(name) {
    /**
     * The function to use to use protect or unprotect a single SRT(C)P packet.
     */
    var transformer: AbstractSrtpTransformer<*>? = null

    /**
     * We'll cache all packets that come through before [transformer]
     * gets set so that we don't lose any packets at the beginning
     * (likely a keyframe).
     * The value is initialized with a new list and changes to [null] once the cached packets are read.
     */
    private var cachedPackets: MutableList<PacketInfo>? = mutableListOf()

    /**
     * Transforms a list of packets using [#transformer]
     */
    private fun transformList(packetInfos: List<PacketInfo>): List<PacketInfo> {
        val transformedPackets = mutableListOf<PacketInfo>()
        packetInfos.forEach { packetInfo ->
            if (transformer?.transform(packetInfo) == true) {
                transformedPackets.add(packetInfo)
            }
        }
        return transformedPackets
    }

    private var firstPacketReceivedTimestamp = -1L
    private var firstPacketForwardedTimestamp = -1L
    /**
     * How many packets, total, we put into the cache while waiting for the transformer
     * (this includes packets which may have been dropped due to the cache filling up)
     */
    private var numCachedPackets = 0

    override fun transform(packetInfo: PacketInfo): List<PacketInfo> {
        if (firstPacketReceivedTimestamp == -1L) {
            firstPacketReceivedTimestamp = System.currentTimeMillis()
        }
        transformer?.let {
            if (firstPacketForwardedTimestamp == -1L) {
                firstPacketForwardedTimestamp = System.currentTimeMillis()
            }

            // This is necessary in order to guarantee that only one thread accesses this.cachedPackets at a time,
            // while requiring no synchronization in the happy case (when this.cachedPackets has been set to null).
            var cachedPackets = this.cachedPackets
            if (cachedPackets != null) {
                synchronized(cachedPackets) {
                    cachedPackets = this.cachedPackets
                    this.cachedPackets = null

                    if (cachedPackets != null) {
                        cachedPackets!!.add(packetInfo)
                        return transformList(cachedPackets!!)
                    }
                }
            }

            return if (transformer!!.transform(packetInfo))
                listOf(packetInfo) else emptyList()
        } ?: run {
            val cachedPackets = this.cachedPackets
            if (cachedPackets != null) {
                synchronized(cachedPackets) {
                    numCachedPackets++
                    cachedPackets.add(packetInfo)
                    while (cachedPackets.size > 1024) {
                        cachedPackets.removeAt(0).let {
                            packetDiscarded(it)
                        }
                    }
                }
            } else {
                // It is possible by this time this.transformer has been set, and another thread has handled the cached
                // packets. This happens very very rarely, and we are OK dropping a packet in this case.
                packetDiscarded(packetInfo)
            }
            return emptyList()
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_cached_packets", numCachedPackets)
            val timeBetweenReceivedAndForwarded = firstPacketForwardedTimestamp - firstPacketReceivedTimestamp
            addNumber("time_initial_hold_ms", timeBetweenReceivedAndForwarded)
        }
    }

    override fun stop() {
        super.stop()
        var cachedPackets = this.cachedPackets
        if (cachedPackets != null) {
            synchronized(cachedPackets) {
                cachedPackets.forEach { packetDiscarded(it) }
                cachedPackets.clear()
                this.cachedPackets = null
            }
        }
        transformer?.close()
    }
}
