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

package org.jitsi.nlj.dtls

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.ConsumerNode
import org.jitsi.nlj.transform.node.incoming.DtlsReceiver
import org.jitsi.nlj.transform.node.outgoing.DtlsSender
import org.jitsi.rtp.UnparsedPacket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.Security
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * This class sets up a DTLS server and a DTLS client, has the client connect
 * to the waiting server and then send a message.  Once the server receives
 * the client's message, it sends a message in response.  Both messages are
 * expected and verified to have been received and be correct.
 */
class DtlsServerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    private val debugEnabled = true

    fun debug(s: String) {
        if (debugEnabled) {
            println(s)
        }
    }

    init {
        val dtlsServer = DtlsServer()
        val dtlsClient = DtlsClientStack()

        val serverSender = DtlsSender(dtlsServer)
        val serverReceiver = DtlsReceiver(dtlsServer)

        val clientSender = DtlsSender(dtlsClient)
        val clientReceiver = DtlsReceiver(dtlsClient)

        // The server and client senders are connected directly to their
        // peer's receiver
        serverSender.attach(object : ConsumerNode("server network") {
            override fun consume(packetInfo: PacketInfo) {
                clientReceiver.processPacket(packetInfo)
            }
        })
        clientSender.attach(object : ConsumerNode("client network") {
            override fun consume(packetInfo: PacketInfo) {
                serverReceiver.processPacket(packetInfo)
            }
        })

        val serverThread = thread {
            debug("Server accepting")
            dtlsServer.accept()
            debug("Server accepted connection")
        }

        // We attach a consumer to each peer's receiver to consume the DTLS app packet
        // messages
        val serverReceivedData = CompletableFuture<String>()
        val serverToClientMessage = "Goodbye, world"
        serverReceiver.attach(object : ConsumerNode("server incoming app packets") {
            override fun consume(packetInfo: PacketInfo) {
                val packetData = ByteBuffer.wrap(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Server received message: '$receivedStr'")
                serverReceivedData.complete(receivedStr)
                serverSender.processPacket(PacketInfo(UnparsedPacket(serverToClientMessage.toByteArray())))
            }
        })

        val clientReceivedData = CompletableFuture<String>()
        clientReceiver.attach(object : ConsumerNode("client incoming app packets") {
            override fun consume(packetInfo: PacketInfo) {
                val packetData = ByteBuffer.wrap(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
                val receivedStr = StandardCharsets.UTF_8.decode(packetData).toString()
                debug("Client received message: '$receivedStr'")
                clientReceivedData.complete(receivedStr)
            }
        })


        debug("Client connecting")
        dtlsClient.connect()
        debug("Client connected, sending message")
        val clientToServerMessage = "Hello, world"
        dtlsClient.sendDtlsAppData(PacketInfo(UnparsedPacket(clientToServerMessage.toByteArray())))

        serverReceivedData.get(5, TimeUnit.SECONDS) shouldBe clientToServerMessage
        clientReceivedData.get(5, TimeUnit.SECONDS) shouldBe serverToClientMessage

        serverThread.join()
    }

}