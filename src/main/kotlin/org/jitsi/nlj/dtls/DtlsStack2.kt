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

import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

class DtlsStack2(
    parentLogger: Logger
) {

    private val logger = createChildLogger(parentLogger)
    private val roleSet = CompletableFuture<Unit>()

    /**
     * The certificate info for this particular [DtlsStack] instance. We save it in a local val because the global one
     * might be refreshed.
     */
    private val certificateInfo = DtlsStack2.certificateInfo

    val localFingerprintHashFunction: String
        get() = certificateInfo.localFingerprintHashFunction

    val localFingerprint: String
        get() = certificateInfo.localFingerprint

    /**
     * The remote fingerprints sent to us over the signaling path.
     */
    var remoteFingerprints: Map<String, String> = HashMap()

    /**
     * A handler which will be invoked when DTLS application data is received
     */
    var incomingDataHandler: IncomingDataHandler? = null

    /**
     * The method [DtlsStack2] will invoke when it wants to send DTLS data out onto the network.
     */
    var sender: ((ByteArray, Int, Int) -> Unit)? = null

    private val incomingProtocolData = LinkedBlockingQueue<ByteBuffer>()

    /**
     * The [DtlsRole] 'plugin' that will determine how this stack operates (as a client
     * or a server).  A call to [actAsClient] or [actAsServer] must be made to fill out
     * this role and successfully call [start]
     */
    var role: DtlsRole? = null
        private set

    /**
     * A buffer we'll use to receive data from [dtlsTransport].
     */
    private val dtlsAppDataBuf = ByteArray(1500)

    /**
     * The negotiated DTLS transport.  This is used to read and write DTLS application data.
     */
    private var dtlsTransport: DTLSTransport? = null

    /**
     * The handler to be invoked when the DTLS handshake is complete.  A [ByteArray]
     * containing the SRTP keying material is passed
     */
    private var handshakeCompleteHandler: (Int, TlsRole, ByteArray) -> Unit = { _, _, _ -> }

    private val datagramTransport: DatagramTransport = DatagramTransportImpl(
        incomingProtocolData,
        logger
    )

    /**
     * Install a handler to be invoked when the DTLS handshake is finished.
     *
     * NOTE this MUST be called before calling either [actAsServer] or
     * [actAsClient]!
     */
    fun onHandshakeComplete(handler: (Int, TlsRole, ByteArray) -> Unit) {
        handshakeCompleteHandler = handler
    }

    fun actAsServer() {
        role = DtlsServer(
            datagramTransport,
            certificateInfo,
            handshakeCompleteHandler,
            this::verifyAndValidateRemoteCertificate,
            logger
        )
        roleSet.complete(Unit)
    }

    fun actAsClient() {
        role = DtlsClient(
            datagramTransport,
            certificateInfo,
            handshakeCompleteHandler,
            this::verifyAndValidateRemoteCertificate,
            logger
        )
        roleSet.complete(Unit)
    }
    /**
     * 'start' this stack, in whatever role it has been told to operate (client or server).  If a role
     * has not yet been yet (via [actAsServer] or [actAsClient]), then it will block until the role
     * has been set.
     */
    fun start() {
        roleSet.thenRun {
            // There is a bit of a race here: It's technically possible the
            // far side could finish the handshake and send a message before
            // this side assigns dtlsTransport here.  If so, that message
            // would be passed to #processIncomingProtocolData and put in
            // incomingProtocolData, but, since dtlsTransport won't be set
            // yet, we won't 'receive' it yet.  The message isn't lost, but
            // won't be received until another message comes after
            // dtlsTransport has been set.
            dtlsTransport = role?.start()
        }
    }

    /**
     * Checks that a specific [Certificate] matches the remote fingerprints sent to us over the signaling path.
     */
    private fun verifyAndValidateRemoteCertificate(remoteCertificate: Certificate?) {
        remoteCertificate?.let {
            DtlsUtils.verifyAndValidateCertificate(it, remoteFingerprints)
            // The above throws an exception if the checks fail.
            logger.cdebug { "Fingerprints verified." }
        }
    }

    fun sendApplicationData(data: ByteArray, off: Int, len: Int) {
        dtlsTransport?.send(data, off, len)
    }

    /**
     * We get 'pushed' the data from a lower transport layer, but bouncycastle wants to 'pull' the data
     * itself.  To mimic this, we put the received data into a queue, and then 'pull' it through ourselves by
     * calling 'receive' on the negotiated [DTLSTransport].
     */
    fun processIncomingProtocolData(data: ByteArray, off: Int, len: Int) {
        incomingProtocolData.add(ByteBuffer.wrap(data, off, len))
        var bytesReceived: Int
        do {
            bytesReceived = dtlsTransport?.receive(dtlsAppDataBuf, 0, 1500, 1) ?: -1
            if (bytesReceived > 0) {
                incomingDataHandler?.dataReceived(dtlsAppDataBuf, 0, bytesReceived)
            }
        } while (bytesReceived > 0)
    }

    companion object {
        /**
         * Because generating the certificateInfo can be expensive, we generate a single
         * one to be used everywhere which expires in 24 hours (when we'll generate
         * another one).
         */
        private val syncRoot: Any = Any()
        private var certificateInfo: CertificateInfo = DtlsUtils.generateCertificateInfo()
            get() = synchronized(syncRoot) {
                val expirationPeriodMs = Duration.ofDays(1).toMillis()
                if (field.creationTimestampMs + expirationPeriodMs < System.currentTimeMillis()) {
                    // TODO: avoid creating our own thread
                    thread { field = DtlsUtils.generateCertificateInfo() }
                }
                return field
            }
    }

    inner class DatagramTransportImpl(
        private val dataQueue: LinkedBlockingQueue<ByteBuffer>,
        parentLogger: Logger
    ) : DatagramTransport {
        private val logger = createChildLogger(parentLogger)
        override fun receive(buf: ByteArray, off: Int, len: Int, waitMillis: Int): Int {
            val data = dataQueue.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS) ?: return -1
            val length = min(len, data.limit())
            if (length < data.limit()) {
                logger.warn("Passed buffer size ($len) was too small to hold incoming data size (${data.limit()}; " +
                    "data was truncated")
            }
            System.arraycopy(data.array(), data.arrayOffset(), buf, off, length)
            return length
        }

        override fun send(buf: ByteArray, off: Int, len: Int) {
            this@DtlsStack2.sender?.invoke(buf, off, len)
        }

        /**
         * Receive limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
         */
        override fun getReceiveLimit(): Int = 1500 - 20 - 8

        /**
         * Send limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
         */
        override fun getSendLimit(): Int = 1500 - 84 - 8

        override fun close() {}
    }

    interface IncomingDataHandler {
        /**
         * Notify the handler that data has been received.  The handler does *not* own the passed buffer,
         * and must copy from it if it wants to use the data after the call has finished
         */
        fun dataReceived(data: ByteArray, off: Int, len: Int)
    }
}
