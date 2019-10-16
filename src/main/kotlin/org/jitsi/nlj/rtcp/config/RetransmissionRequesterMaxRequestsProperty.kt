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

package org.jitsi.nlj.rtcp.config

import org.jitsi.nlj.config.JmtConfig
import org.jitsi.utils.config.AbstractConfigProperty
import org.jitsi.utils.config.PropertyConfig

class RetransmissionRequesterMaxRequestsProperty : AbstractConfigProperty<Int>(propConfig) {
    companion object {
        private const val propName = "jmt.rtcp.rtx.max-requests"
        private val singleton = RetransmissionRequesterMaxRequestsProperty()

        private val propConfig = PropertyConfig<Int>()
            .suppliedBy { JmtConfig.config.getInt(propName) }
            .readOnce()
            .throwIfNotFound()

        fun getValue(): Int = singleton.get()
    }
}