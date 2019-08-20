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

class ContextLogger(private val context: String) {
    private val logger = Logger.getLogger(context)

    val isInfoEnabled: Boolean
        get() = logger.isInfoEnabled

    val isDebugEnabled: Boolean
        get() = logger.isDebugEnabled

    val isWarnEnabled: Boolean
        get() = logger.isWarnEnabled

    val isTraceEnabled: Boolean
        get() = logger.isTraceEnabled

    fun createSubcontext(context: String): ContextLogger {
        return ContextLogger("${this.context} $context")
    }

    fun error(msg: String) = logger.error(msg)

    fun warn(msg: String) = logger.warn(msg)

    fun info(msg: String) = logger.info(msg)

    fun debug(msg: String) = logger.debug(msg)

    fun trace(msg: String) = logger.trace(msg)

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
