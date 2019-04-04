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

import org.bouncycastle.tls.DTLSServerProtocol
import org.bouncycastle.tls.DTLSTransport
import org.jitsi.nlj.srtp.TlsRole
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo

class DtlsServer(
    private val dtlsServerProtocol: DTLSServerProtocol = DTLSServerProtocol()
) : DtlsStack() {

    private val tlsServer: TlsServerImpl = TlsServerImpl(this::verifyAndValidateRemoteCertificate)

    fun accept() {
        try {
            dtlsTransport = dtlsServerProtocol.accept(tlsServer, this)
            logger.cinfo { "DTLS handshake finished" }
            handshakeCompleteHandler(tlsServer.chosenSrtpProtectionProfile, TlsRole.SERVER, tlsServer.srtpKeyingMaterial)
        } catch (t: Throwable) {
            logger.cerror { "Error during DTLS connection: $t" }
            throw t
        }
    }
}