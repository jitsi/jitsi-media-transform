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

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.protocol.ProtocolStack
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.BufferPool
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.utils.logging2.Logger

/**
 * Represents a single instance of a DTLS stack for a given connection.  This class also acts as the [DatagramTransport]
 * used by the underlying DTLS library in order to send and receive DTLS packets.  Users of this class need to handle
 * passing incoming DTLS packets into the stack, as well as handling packets the stack wants to send out.  The passing
 * of incoming packets is done via calling [DtlsStack.processIncomingProtocolData].  The handling of outgoing packets is done by
 * assigning a handler to the [DtlsStack.onOutgoingProtocolData] member.  Incoming packets may be either control packets
 * (terminated by the stack itself) or app packets which have been sent over DTLS (SCTP packets, for example).  After
 * passing incoming packets to the stack via [DtlsStack.processIncomingProtocolData], any app packets ready for further processing
 * will be returned.  Outgoing packets can be sent via [DtlsStack.sendApplicationData].
 *
 * An example of passing incoming DTLS packets through the stack:
 *
 *  --> Recv 'dtlsPacket' from the network and pass it into the stack:
 *  val appPackets = dtlsStack.processIncomingProtocolData(listOf(dtlsPacket))
 *  if (appPackets.isNotEmpty()) {
 *    // Process the app packets
 *  }
 *
 *  An example of sending app packets out via the DTLS stack:
 *  dtlsStack.onOutgoingData = { outgoingDtlsPacket ->
 *    // Work to send the packets out
 *  }
 *  val dtlsAppPacket = ...
 *  dtlsStack.sendApplicationData(dtlsAppPacket)
 *
 */
class DtlsStack(
    parentLogger: Logger
) : ProtocolStack, DatagramTransport, NodeStatsProducer {
    private val logger = createChildLogger(parentLogger)
    private val roleSet = CompletableFuture<Unit>()

    /**
     * The certificate info for this particular [DtlsStack] instance. We save it in a local val because the global one
     * might be refreshed.
     */
    private val certificateInfo = DtlsStack.certificateInfo

    val localFingerprintHashFunction: String
        get() = certificateInfo.localFingerprintHashFunction

    val localFingerprint: String
        get() = certificateInfo.localFingerprint

    /**
     * The remote fingerprints sent to us over the signaling path.
     */
    var remoteFingerprints: Map<String, String> = HashMap()

    /**
     * Checks that a specific [Certificate] matches the remote fingerprints sent to us over the signaling path.
     */
    protected fun verifyAndValidateRemoteCertificate(remoteCertificate: Certificate?) {
        remoteCertificate?.let {
            DtlsUtils.verifyAndValidateCertificate(it, remoteFingerprints)
            // The above throws an exception if the checks fail.
            logger.cdebug { "Fingerprints verified." }
        }
    }

    /**
     * Incoming DTLS packets received from the network are stored here via [processIncomingProtocolData].  They are read
     * by the underlying DTLS library via the [receive] method, which the library calls to receive incoming data.
     */
    private val incomingProtocolData = LinkedBlockingQueue<PacketInfo>()
    // TODO convert to single packet?
    private var onOutgoingProtocolData: (List<PacketInfo>) -> Unit = {}

    override fun onOutgoingProtocolData(handler: (List<PacketInfo>) -> Unit) {
        onOutgoingProtocolData = handler
    }

    /**
     * The negotiated DTLS transport.  This is used to read and write DTLS app data.
     */
    private var dtlsTransport: DTLSTransport? = null

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
            this,
            certificateInfo,
            handshakeCompleteHandler,
            this::verifyAndValidateRemoteCertificate,
            logger
        )
        roleSet.complete(Unit)
    }

    fun actAsClient() {
        role = DtlsClient(
            this,
            certificateInfo,
            handshakeCompleteHandler,
            this::verifyAndValidateRemoteCertificate,
            logger
        )
        roleSet.complete(Unit)
    }

    /**
     * The handler to be invoked when the DTLS handshake is complete.  A [ByteArray]
     * containing the SRTP keying material is passed
     */
    private var handshakeCompleteHandler: (Int, TlsRole, ByteArray) -> Unit = { _, _, _ -> }

    override fun processIncomingProtocolData(packetInfo: PacketInfo): List<PacketInfo> {
        incomingProtocolData.add(packetInfo)
        var bytesReceived: Int
        val outPackets = mutableListOf<PacketInfo>()
        do {
            bytesReceived = dtlsTransport?.receive(dtlsAppDataBuf, 0, 1500, 1) ?: -1
            if (bytesReceived > 0) {
                val bufCopy = BufferPool.getBuffer(bytesReceived)
                System.arraycopy(dtlsAppDataBuf, 0, bufCopy, 0, bytesReceived)
                outPackets.add(PacketInfo(DtlsProtocolPacket(bufCopy, 0, bytesReceived)))
            }
        } while (bytesReceived > 0)
        return outPackets
    }

    override fun sendApplicationData(packetInfo: PacketInfo) {
        dtlsTransport?.send(packetInfo.packet.buffer, packetInfo.packet.offset, packetInfo.packet.length)
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

    override fun close() {
        incomingProtocolData.forEach {
            BufferPool.returnBuffer(it.packet.buffer)
        }
    }

    /**
     * Receive limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getReceiveLimit(): Int = 1500 - 20 - 8

    /**
     * Send limit computation copied from [org.bouncycastle.crypto.tls.UDPTransport]
     */
    override fun getSendLimit(): Int = 1500 - 84 - 8

    override fun receive(buf: ByteArray, off: Int, length: Int, waitMillis: Int): Int {
        val packetInfo = incomingProtocolData.poll(waitMillis.toLong(), TimeUnit.MILLISECONDS) ?: return -1
        val packet = packetInfo.packet
        System.arraycopy(packet.buffer, packet.offset, buf, off, packet.length)

        BufferPool.returnBuffer(packetInfo.packet.buffer)

        return packet.length
    }

    /**
     * Send an outgoing DTLS packet (already processed by the DTLS stack) out via invoking
     * a handler.
     *
     * We have to use a synchronous callback approach here, as some packets originate
     * from within the stack itself (e.g. during connect) and if we put the packets in,
     * for example, a queue, we'd still have to fire some trigger for something to come
     * in and read them.
     */
    override fun send(buf: ByteArray, off: Int, length: Int) {
        // The buf coming from here will belong to usrsctp, so make sure we copy
        // into a buffer of our own before forwarding.
        // NOTE: the reason we have to copy into our own before before forwarding
        // is that this packet will end up in the same place as a packet that
        // came from the pool which has to be returned.  Since we can't tell if
        // a buffer came from the pool or not, we'll need to always return it.
        val newBuf = BufferPool.getBuffer(length)
        System.arraycopy(buf, off, newBuf, 0, length)
        val packet = PacketInfo(UnparsedPacket(newBuf, 0, length))
        onOutgoingProtocolData(listOf(packet))
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("DtlsStack").apply {
        addBlock(NodeStatsBlock("localFingerprint").apply {
            addString(certificateInfo.localFingerprintHashFunction, certificateInfo.localFingerprint)
        })
        addBlock(NodeStatsBlock("remoteFingerprints").apply {
            remoteFingerprints.forEach { (hash, fp) -> addString(hash, fp) }
        })
        addString("role", (role?.javaClass ?: "null").toString())
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
                    Thread { field = DtlsUtils.generateCertificateInfo() }.start()
                }
                return field
            }
    }
}
