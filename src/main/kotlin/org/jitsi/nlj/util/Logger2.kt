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

import org.jitsi.utils.logging2.Logger

/**
 * Note that, although the logger now supports taking a message supplier
 * argument, these methods are still more efficient as they're inline (and
 * therefore don't require constructing a lambda object)
 */
inline fun Logger.cinfo(msg: () -> String) {
    if (isInfoEnabled) {
        this.info(msg())
    }
}

inline fun Logger.cdebug(msg: () -> String) {
    if (isDebugEnabled) {
        this.debug(msg())
    }
}

inline fun Logger.cwarn(msg: () -> String) {
    if (isWarnEnabled) {
        this.warn(msg())
    }
}

inline fun Logger.cerror(msg: () -> String) {
    this.error(msg())
}
