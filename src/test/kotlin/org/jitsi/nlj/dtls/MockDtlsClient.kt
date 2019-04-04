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

import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.HashAlgorithm
import org.bouncycastle.tls.SignatureAlgorithm
import org.bouncycastle.tls.SignatureAndHashAlgorithm
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCryptoParameters
import org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.security.SecureRandom

class MockDtlsClient : DefaultTlsClient(BcTlsCrypto(SecureRandom())) {

    private val certificateInfo = DtlsUtils.generateCertificateInfo()

    override fun getAuthentication(): TlsAuthentication {
        return object : TlsAuthentication {
            override fun getClientCredentials(certificateRequest: CertificateRequest?): TlsCredentials {
                return BcDefaultTlsCredentialedSigner(
                    TlsCryptoParameters(context),
                    (context.crypto as BcTlsCrypto),
                    certificateInfo.keyPair.private,
                    certificateInfo.certificate,
                    SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa)
                )
            }

            override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {}
        }
    }
}

