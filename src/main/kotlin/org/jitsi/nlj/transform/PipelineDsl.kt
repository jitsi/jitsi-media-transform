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
package org.jitsi.nlj.transform

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.DemuxerNode
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.transform.node.ConditionalPacketPath
import org.jitsi.nlj.transform.node.ExclusivePathDemuxer
import org.jitsi.rtp.Packet


//TODO: look into @DslMarker to prevent inner dsl builders from accidentally setting parent
// member variables when they overlap

fun DemuxerNode.packetPath(b: ConditionalPacketPath.() -> Unit) {
    this.addPacketPath(ConditionalPacketPath().apply(b))
}

class PipelineBuilder {
    private var head: Node? = null
    private var tail: Node? = null

    private fun addNode(node: Node) {
        if (head == null) {
            head = node
        }
        if (tail is DemuxerNode) {
            // In the future we could separate 'input/output' nodes from purely
            // input nodes and use that here?
            throw Exception("Cannot attach node to a DemuxerNode")
        }
        tail?.attach(node)
        tail = node
    }

    fun node(node: Node) = addNode(node)

    /**
     * simpleNode allows the caller to pass in a block of code which takes a list of input
     * [Packet]s and returns a list of output [Packet]s to be forwarded to the next
     * [Node]
     */
    fun simpleNode(name: String, packetHandler: (List<PacketInfo>) -> List<PacketInfo>) {
        val node = object : Node(name) {
            override fun doProcessPackets(p: List<PacketInfo>): List<PacketInfo> {
                return packetHandler.invoke(p)
            }
        }
        addNode(node)
    }

    fun demux(name: String, block: DemuxerNode.() -> Unit) {
        val demuxer = ExclusivePathDemuxer(name).apply(block)
        addNode(demuxer)
    }

    fun demux(name: String, vararg paths: ConditionalPacketPath) {
        val demuxer = DemuxerNode(name)
        for (path in paths)
            demuxer.addPacketPath(path)
    }

    fun build(): Node = head!!
}

fun pipeline(block: PipelineBuilder.() -> Unit): Node = PipelineBuilder().apply(block).build()
