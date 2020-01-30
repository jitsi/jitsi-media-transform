/*
 * Copyright @ 2019 - present 8x8, Inc.
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

package org.jitsi.nlj.srtp

import java.util.concurrent.ConcurrentHashMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.nlj.util.cwarn
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.srtp.BaseSrtpCryptoContext
import org.jitsi.srtp.SrtcpCryptoContext
import org.jitsi.srtp.SrtpContextFactory
import org.jitsi.srtp.SrtpCryptoContext
import org.jitsi.srtp.SrtpErrorStatus
import org.jitsi.utils.logging2.Logger

/**
 * Implements the methods common to all 4 transformer implementation (encrypt/decrypt for SRTP/SRTCP)
 */
abstract class AbstractSrtpTransformer<CryptoContextType : BaseSrtpCryptoContext>(
    protected val contextFactory: SrtpContextFactory,
    protected val logger: Logger
) {
    /**
     * All the known SSRC's corresponding SrtpCryptoContexts
     */
    private val contexts: MutableMap<Long, CryptoContextType> = ConcurrentHashMap()

    fun close() {
        synchronized(contexts) {
            contextFactory.close()
            contexts.values.forEach { it.close() }
        }
    }

    /**
     * Gets the context for a specific SSRC and index.
     */
    protected fun getContext(ssrc: Long, index: Long): CryptoContextType? {
        synchronized(contexts) {
            contexts[ssrc]?.let { return it }

            val derivedContext = deriveContext(ssrc, index) ?: run {
                logger.warn("Failed to derive context for $ssrc $index")
                return null
            }
            contexts[ssrc] = derivedContext
            return derivedContext
        }
    }

    /**
     * Derives a new context for a specific SSRC and index.
     */
    protected abstract fun deriveContext(ssrc: Long, index: Long): CryptoContextType?

    /**
     * Gets the context to use for a specific packet.
     */
    protected abstract fun getContext(packetInfo: PacketInfo): CryptoContextType?

    /**
     * Does the actual transformation of a packet, with a specific context.
     */
    protected abstract fun transform(packetInfo: PacketInfo, context: CryptoContextType): SrtpErrorStatus

    /**
     * Transforms a packet, returns [SrtpErrorStatus.OK] on success or another [SrtpErrorStatus] on failure.
     */
    fun transform(packetInfo: PacketInfo): SrtpErrorStatus {
        val context = getContext(packetInfo) ?: return SrtpErrorStatus.FAIL

        return transform(packetInfo, context)
    }
}

/**
 * Implements methods common for the two SRTP transformer implementations.
 */
abstract class SrtpTransformer(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : AbstractSrtpTransformer<SrtpCryptoContext>(contextFactory, logger) {

    override fun deriveContext(ssrc: Long, index: Long): SrtpCryptoContext? =
            contextFactory.deriveContext(ssrc.toInt(), 0) ?: null

    override fun getContext(packetInfo: PacketInfo): SrtpCryptoContext? {
        val rtpPacket: RtpPacket = packetInfo.packet as? RtpPacket ?: run {
            logger.cwarn { "Can not handle non-RTP packet: ${packetInfo.packet.javaClass}" }
            return null
        }
        return getContext(rtpPacket.ssrc, rtpPacket.sequenceNumber.toLong())
    }
}

/**
 * Implements methods common for the two SRTCP transformer implementations.
 */
abstract class SrtcpTransformer(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : AbstractSrtpTransformer<SrtcpCryptoContext>(contextFactory, logger) {

    override fun deriveContext(ssrc: Long, index: Long): SrtcpCryptoContext? =
            contextFactory.deriveControlContext(ssrc.toInt()) ?: null

    override fun getContext(packetInfo: PacketInfo): SrtcpCryptoContext? {
        // Contrary to RTP packets, RTCP packets do not get parsed before they are
        // decrypted. So (if this is a decrypting transformer) we are working with
        // an UnparsedPacket here and need to read the SSRC manually.
        val senderSsrc = RtcpHeader.getSenderSsrc(packetInfo.packet.getBuffer(), packetInfo.packet.getOffset())
        return getContext(senderSsrc, 0)
    }
}

/**
 * A transformer which decrypts SRTCP packets.
 */
class SrtcpDecryptTransformer private constructor(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : SrtcpTransformer(contextFactory, createChildLogger(logger)) {

    override fun transform(packetInfo: PacketInfo, context: SrtcpCryptoContext): SrtpErrorStatus {
        return context.reverseTransformPacket(packetInfo.packet).apply {
            packetInfo.resetPayloadVerification()
        }
    }

    companion object {
        operator fun invoke(contextFactory: SrtpContextFactory, parentLogger: Logger): SrtcpDecryptTransformer =
            SrtcpDecryptTransformer(contextFactory, createChildLogger(parentLogger))
    }
}

/**
 * A transformer which encrypts RTCP packets (producing SRTCP packets). Note that as opposed to the other transformers,
 * this one replaces the [Packet].
 */
class SrtcpEncryptTransformer private constructor(
    contextFactory: SrtpContextFactory,
    parentLogger: Logger
) : SrtcpTransformer(contextFactory, createChildLogger(parentLogger)) {

    override fun transform(packetInfo: PacketInfo, context: SrtcpCryptoContext): SrtpErrorStatus {
        return context.transformPacket(packetInfo.packet).apply {
            // We convert the encrypted RTCP packet to an UnparsedPacket because
            // we don't want any of the RTCP fields trying to parse the data
            // (since it's now encrypted)
            // TODO: better way we can do this?  it's not typically a problem
            // in the pipeline's usage, but it's a bit of a landmine since by
            // accessing the packet it can try and parse the fields.
            packetInfo.packet = packetInfo.packet.toOtherType(::UnparsedPacket)
            packetInfo.resetPayloadVerification()
        }
    }

    companion object {
        operator fun invoke(contextFactory: SrtpContextFactory, parentLogger: Logger): SrtcpEncryptTransformer =
            SrtcpEncryptTransformer(contextFactory, createChildLogger(parentLogger))
    }
}

/**
 * A transformer which decrypts SRTP packets. Note that it expects the [Packet] to have already been parsed as
 * [RtpPacket].
 */
class SrtpDecryptTransformer private constructor(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : SrtpTransformer(contextFactory, logger) {

    override fun transform(packetInfo: PacketInfo, context: SrtpCryptoContext): SrtpErrorStatus {
        // For silence packets we update the ROC (if authentication passes), but don't decrypt
        return context.reverseTransformPacket(packetInfo.packetAs(), packetInfo.shouldDiscard).apply {
            packetInfo.resetPayloadVerification()
        }
    }

    companion object {
        operator fun invoke(contextFactory: SrtpContextFactory, parentLogger: Logger): SrtpDecryptTransformer =
            SrtpDecryptTransformer(contextFactory, createChildLogger(parentLogger))
    }
}

/**
 * A transformer which encrypts RTP packets (producing SRTP packets).
 */
class SrtpEncryptTransformer private constructor(
    contextFactory: SrtpContextFactory,
    logger: Logger
) : SrtpTransformer(contextFactory, logger) {

    override fun transform(packetInfo: PacketInfo, context: SrtpCryptoContext): SrtpErrorStatus {
        return context.transformPacket(packetInfo.packetAs()).apply {
            packetInfo.packet = packetInfo.packet.toOtherType(::UnparsedPacket)
            packetInfo.resetPayloadVerification()
        }
    }

    companion object {
        operator fun invoke(contextFactory: SrtpContextFactory, parentLogger: Logger): SrtpEncryptTransformer =
            SrtpEncryptTransformer(contextFactory, createChildLogger(parentLogger))
    }
}

/**
 * Holds the four SRTP-related transformers for a session.
 */
data class SrtpTransformers(
    val srtpDecryptTransformer: SrtpDecryptTransformer,
    val srtpEncryptTransformer: SrtpEncryptTransformer,
    val srtcpDecryptTransformer: SrtcpDecryptTransformer,
    val srtcpEncryptTransformer: SrtcpEncryptTransformer
)
