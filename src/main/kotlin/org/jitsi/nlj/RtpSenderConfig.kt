package org.jitsi.nlj

import org.jitsi.config.newConfigAttributes
import org.jitsi.utils.config.SimpleProperty

class RtpSenderConfig {
    class Config {
        companion object {

            class RtpSenderQueueSizeProperty : SimpleProperty<Int>(
                    newConfigAttributes {
                        readOnce()
                        name("jmt.transceiver.recv.q-size")
                    }
            )
            private val rtpSenderQueueSizeProperty =
                    RtpSenderQueueSizeProperty()

            @JvmStatic
            fun rtpSenderQueueSizeProperty(): Int = rtpSenderQueueSizeProperty.value
        }
    }
}
