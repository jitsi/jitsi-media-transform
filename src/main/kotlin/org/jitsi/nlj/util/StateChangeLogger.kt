package org.jitsi.nlj.util

import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cinfo

/**
 * Class that logs when state first becomes false, or changes from false to true,
 * without logging on every packet.
 */
class StateChangeLogger(
    val desc: String,
    val logger: Logger
) {
    var state: Boolean? = null

    fun setState(newState: Boolean, instance: Any, instanceDesc: () -> String) {
        if (state != newState) {
            if (newState == false) {
                logger.cinfo { "Packet $instance does not have $desc.  ${instanceDesc()}" }
            } else if (state != null) {
                logger.cinfo { "Packet $instance has $desc when source previously did not.  ${instanceDesc()}" }
            }
            state = newState
        }
    }
}
