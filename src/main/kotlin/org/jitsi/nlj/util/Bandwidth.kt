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

/**
 * [Bandwidth] models a current bandwidth, represented as a rate
 * of bits per second.
 */
class Bandwidth(
    bps: Float
) {
    var bps: Float = bps
        private set

    val kbps: Float = bps / 1000
    val mbps: Float = bps / (1000 * 1000)

    operator fun minus(other: Bandwidth): Bandwidth =
        Bandwidth(bps - other.bps)

    operator fun minusAssign(other: Bandwidth) {
        bps -= other.bps
    }

    operator fun plus(other: Bandwidth): Bandwidth =
        Bandwidth(bps + other.bps)

    operator fun plusAssign(other: Bandwidth) {
        bps += other.bps
    }

    operator fun times(other: Bandwidth): Bandwidth =
        Bandwidth(bps * other.bps)

    operator fun timesAssign(other: Bandwidth) {
        bps *= other.bps
    }

    operator fun div(other: Bandwidth): Bandwidth =
        Bandwidth(bps / other.bps)

    operator fun divAssign(other: Bandwidth) {
        bps /= other.bps
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Bandwidth) {
            return false
        }
        return this.bps == other.bps
    }
}

fun Int.bps(): Bandwidth = Bandwidth(this.toFloat())
fun Int.kbps(): Bandwidth = Bandwidth(this.toFloat() * 1000)
fun Int.mbps(): Bandwidth = Bandwidth(this.toFloat() * 1000 * 1000)

fun Float.bps(): Bandwidth = Bandwidth(this)
fun Float.kbps(): Bandwidth = Bandwidth(this * 1000)
fun Float.mbps(): Bandwidth = Bandwidth(this * 1000 * 1000)

fun Double.bps(): Bandwidth = Bandwidth(this.toFloat())
fun Double.kbps(): Bandwidth = Bandwidth(this.toFloat() * 1000)
fun Double.mbps(): Bandwidth = Bandwidth(this.toFloat() * 1000 * 1000)

fun Long.bps(): Bandwidth = Bandwidth(this.toFloat())
fun Long.kbps(): Bandwidth = Bandwidth(this.toFloat() * 1000)
fun Long.mbps(): Bandwidth = Bandwidth(this.toFloat() * 1000 * 1000)
