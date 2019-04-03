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

import org.bouncycastle.crypto.tls.DefaultTlsEncryptionCredentials
import org.bouncycastle.crypto.tls.TlsContext
import org.bouncycastle.tls.Certificate
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.ClientCertificateType
import org.bouncycastle.tls.DefaultTlsServer
import org.bouncycastle.tls.ExporterLabel
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.SRTPProtectionProfile
import org.bouncycastle.tls.TlsCredentialedDecryptor
import org.bouncycastle.tls.TlsCredentialedSigner
import org.bouncycastle.tls.TlsSRTPUtils
import org.bouncycastle.tls.TlsSession
import org.bouncycastle.tls.TlsUtils
import org.bouncycastle.tls.UseSRTPData
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedDecryptor
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.jitsi.nlj.srtp.SrtpUtil
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Hashtable

class TlsServerImpl : DefaultTlsServer(BcTlsCrypto(SecureRandom())) {

    private val logger = getLogger(this.javaClass)

    private var session: TlsSession? = null

    /**
     * Only set after a handshake has completed
     */
    lateinit var srtpKeyingMaterial: ByteArray

    private val srtpProtectionProfiles = intArrayOf(
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
        SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32
    )

    var chosenSrtpProtectionProfile: Int = 0

    override fun getSessionToResume(sessionID: ByteArray?): TlsSession? {
        return session
        //TODO: do we need to map multiple sessions (per sessionID?)
//        return super.getSessionToResume(sessionID)
    }

    override fun getServerExtensions(): Hashtable<*, *> {
        val extensions = super.clientExtensions ?:
            Hashtable<Int, ByteArray>()
        return extensions.also {
            if (TlsSRTPUtils.getUseSRTPExtension(it) == null) {
                TlsSRTPUtils.addUseSRTPExtension(
                    it,
                    UseSRTPData(srtpProtectionProfiles, TlsUtils.EMPTY_BYTES)
                )
            }
        }
    }

    override fun processClientExtensions(clientExtensions: Hashtable<*, *>?) {
        val useSRTPData = TlsSRTPUtils.getUseSRTPExtension(clientExtensions)
        val protectionProfiles = useSRTPData.protectionProfiles
        chosenSrtpProtectionProfile = DtlsUtils.chooseSrtpProtectionProfile(srtpProtectionProfiles, protectionProfiles)
    }

    override fun getCipherSuites(): IntArray {
        return intArrayOf(
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
            CipherSuite.TLS_DHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA256,
            CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA256,
            CipherSuite.TLS_DHE_RSA_WITH_AES_256_CBC_SHA,
            CipherSuite.TLS_DHE_RSA_WITH_AES_128_CBC_SHA
        )
    }

    override fun getRSAEncryptionCredentials(): TlsCredentialedDecryptor {
        val crypto = context.crypto
        when (crypto) {
            is BcTlsCrypto -> {
                //TODO: save and store this value?
                return BcDefaultTlsCredentialedDecryptor(
                    crypto,
                    DtlsStack.getCertificateInfo().certificate,
                    DtlsStack.getCertificateInfo().keyPair.private
                )
            }
            else -> {
                throw DtlsUtils.DtlsException("Unsupported crypto type: ${crypto.javaClass}")
            }
        }
    }

    override fun getRSASignerCredentials(): TlsCredentialedSigner {
        return BcDefaultTlsCredentialedSigner(

        )
    }

    override fun getCertificateRequest(): CertificateRequest =
        CertificateRequest(shortArrayOf(ClientCertificateType.rsa_sign), null, null)

    override fun notifyHandshakeComplete() {
        super.notifyHandshakeComplete()
        context.resumableSession?.let { newSession ->
            val newSessionIdHex = ByteBuffer.wrap(newSession.sessionID).toHex()

            session?.let { existingSession ->
                if (existingSession.sessionID?.contentEquals(newSession.sessionID) == true) {
                    logger.cinfo { "Resumed DTLS session $newSessionIdHex" }
                }
            } ?: run {
                logger.cinfo { "Established DTLS session $newSessionIdHex" }
                this.session = newSession
            }
        }
        val srtpProfileInformation =
            SrtpUtil.getSrtpProfileInformationFromSrtpProtectionProfile(chosenSrtpProtectionProfile)
        srtpKeyingMaterial = context.exportKeyingMaterial(
            ExporterLabel.dtls_srtp,
            null,
            2 * (srtpProfileInformation.cipherKeyLength + srtpProfileInformation.cipherSaltLength)
        )
    }

    override fun notifyClientVersion(clientVersion: ProtocolVersion?) {
        super.notifyClientVersion(clientVersion)

        logger.cinfo { "Negotiated DTLS version $clientVersion" }
    }

    override fun notifyAlertRaised(alertLevel: Short, alertDescription: Short, message: String?, cause: Throwable?) {
        val stack = with(StringBuffer()) {
            val e = Exception()
            for (el in e.stackTrace) {
                appendln(el.toString())
            }
            toString()
        }
        logger.info(stack)
    }

    override fun notifyAlertReceived(alertLevel: Short, alertDescription: Short) {
        logger.cerror { "TLS Server alert received: $alertLevel $alertDescription" }
    }


    override fun getSupportedVersions(): Array<ProtocolVersion> =
        ProtocolVersion.DTLSv12.downTo(ProtocolVersion.DTLSv10)
}