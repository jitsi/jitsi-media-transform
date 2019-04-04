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

import org.bouncycastle.tls.DatagramTransport
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class PacketData(val buf: ByteArray, val off: Int, val length: Int)

class FakeDatagramTransport : DatagramTransport {
    val incomingQueue = LinkedBlockingQueue<PacketData>()
    var sendFunc: (ByteArray, Int, Int) -> Unit = { _, _, _ -> Unit}
    override fun receive(buf: ByteArray, off: Int, length: Int, waitMillis: Int): Int {
        val pData: PacketData? = incomingQueue.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS)
        pData?.let {
            System.arraycopy(it.buf, it.off, buf, off, Math.min(length, it.length))
        }
        return pData?.length ?: -1
    }

    override fun send(buf: ByteArray, off: Int, length: Int) {
        sendFunc(buf, off, length)
    }

    override fun close() { }

    override fun getReceiveLimit(): Int = 1350

    override fun getSendLimit(): Int = 1350
}
