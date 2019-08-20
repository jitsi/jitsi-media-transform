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

package org.jitsi.nlj.util

import org.jitsi.utils.logging.Logger

class ContextLogger(
    name: String,
    private val context: LogContext
) {
    private val logger = Logger.getLogger(name)

    val isInfoEnabled: Boolean
        get() = logger.isInfoEnabled

    val isDebugEnabled: Boolean
        get() = logger.isDebugEnabled

    val isWarnEnabled: Boolean
        get() = logger.isWarnEnabled

    val isTraceEnabled: Boolean
        get() = logger.isTraceEnabled

    fun error(msg: String) = logger.error("$context $msg")

    fun warn(msg: String) = logger.warn("$context $msg")

    fun info(msg: String) = logger.info("$context $msg")

    fun debug(msg: String) = logger.debug("$context $msg")

    fun trace(msg: String) = logger.trace("$context $msg")

    inline fun cinfo(msg: () -> String) {
        if (isInfoEnabled) {
            info(msg())
        }
    }

    inline fun cdebug(msg: () -> String) {
        if (isDebugEnabled) {
            this.debug(msg())
        }
    }

    inline fun cwarn(msg: () -> String) {
        if (isWarnEnabled) {
            warn(msg())
        }
    }

    inline fun cerror(msg: () -> String) {
        error(msg())
    }

    inline fun ctrace(msg: () -> String) {
        if (isTraceEnabled) {
            trace(msg())
        }
    }
}

/**
 * A [LogContext] contains information which will be logged with every
 * statement.  For now it just includes a prefix, but could be extended
 * to include values of variables contained in a map.
 */
data class LogContext(
    val prefix: String
) {
    override fun toString(): String = prefix

    fun createSubContext(newContext: LogContext) =
        LogContext("$prefix ${newContext.prefix}")

    fun createSubContext(newPrefix: String) =
        LogContext("$prefix $newPrefix")

    companion object {
        val EMPTY = LogContext("")
    }
}
