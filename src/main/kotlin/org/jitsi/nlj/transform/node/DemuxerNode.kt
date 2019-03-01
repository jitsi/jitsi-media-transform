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

import org.jitsi.nlj.transform.NodeVisitor
import kotlin.streams.toList

abstract class DemuxerNode(name: String) : StatsKeepingNode("$name demuxer") {
    protected var transformPaths: MutableSet<ConditionalPacketPath> = mutableSetOf()

    fun addPacketPath(packetPath: ConditionalPacketPath) {
        transformPaths.add(packetPath)
        // DemuxerNode never uses the plain 'next' call since it doesn't have a single 'next'
        // node (it has multiple downstream paths), but we want to make sure the paths correctly
        // see this Demuxer in their 'inputNodes' so that we can traverse the reverse tree
        // correctly, so we call attach here to get the inputNodes wired correctly.
        super.attach(packetPath.path)
    }

    fun removePacketPaths() {
        //TODO: concurrency issues here
        transformPaths.forEach { it.path.removeParent(this) }
        transformPaths.clear()
    }

    override fun attach(node: Node?) = throw Exception()

    override fun visit(visitor: NodeVisitor) {
        visitor.visit(this)
        transformPaths.forEach { conditionalPath ->
            conditionalPath.path.visit(visitor)
        }
    }

    override fun getChildren(): Collection<Node> = transformPaths.stream().map(ConditionalPacketPath::path).toList()
}
