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

@file:JvmName("ClockUtils")

package org.jitsi.nlj.util

import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant

@JvmField
val NEVER: Instant = Instant.MIN

/** Get nanoseconds since the epoch of an Instant.
 *
 * Long can represent nanoseconds-since-the-epoch up through April 2262.
 * If you are still using this code after the year 2262 I apologize.
 */
val Instant.epochNano: Long
    get() {
        val sec = this.epochSecond
        val nano = this.nano

        return sec * 1_000_000_000 + nano
    }

/** Get total nanoseconds of a Duration.
 *
 * Long nanoseconds can represent durations of up to 292 years.
 * If you need durations longer than that please use a different API.
 */
val Duration.totalNanos: Long
    get() {
        val sec = this.seconds
        val nano = this.nano

        return sec * 1_000_000_000 + nano
    }

private val millionthsFormatter by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DecimalFormat(".######")
}

private fun formatMillionths(value: Long): String {
    val neg = value < 0
    val absValue = if (neg) -value else value

    var ret = StringBuilder()
    if (neg) {
        ret.append("-")
    }

    val integer = absValue / 1_000_000
    val millionths = absValue % 1_000_000

    ret.append(integer.toString())

    if (millionths != 0L)
        ret.append(millionthsFormatter.format(millionths / 1e6))

    return ret.toString()
}

fun Instant.formatMilli(): String = formatMillionths(this.epochNano)

fun Duration.formatMilli(): String = formatMillionths(this.totalNanos)
