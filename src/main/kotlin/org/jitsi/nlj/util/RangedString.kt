/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

/** Format a sequence of ints as a ranged string, e.g. "1, 3-8, 9-10" */
/* TODO: it'd be nice to support this for any integer type but I don't know
    if there's a good way to represent this.
 */
fun Iterator<Int>.joinToRangedString(
    separator: CharSequence = ", ",
    rangeSeparator: CharSequence = "-",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    rangeLimit: Int = -1,
    truncated: CharSequence = "..."
): String {
    val buffer = StringBuilder()

    buffer.append(prefix)
    var rangeCount = 0
    var previous: Int? = null
    var inRange = false
    for (element in this) {
        if (previous == null) {
            buffer.append(element.toString())
            rangeCount++
            inRange = false
        } else if (element == previous + 1) {
            inRange = true
        } else {
            if (inRange) {
                buffer.append(rangeSeparator).append(previous.toString())
            }
            inRange = false
            buffer.append(separator)
            rangeCount++
            if (rangeLimit < 0 || rangeCount <= rangeLimit) {
                buffer.append(element.toString())
            } else break
        }
        previous = element
    }
    if (inRange) {
        buffer.append(rangeSeparator).append(previous.toString())
    }
    if (rangeLimit >= 0 && rangeCount > rangeLimit) buffer.append(truncated)
    buffer.append(postfix)
    return buffer.toString()
}

fun Iterable<Int>.joinToRangedString(
    separator: CharSequence = ", ",
    rangeSeparator: CharSequence = "-",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    rangeLimit: Int = -1,
    truncated: CharSequence = "..."
): String = this.iterator().joinToRangedString(separator = separator, rangeSeparator = rangeSeparator, prefix = prefix, postfix = postfix, rangeLimit = rangeLimit, truncated = truncated)

fun Sequence<Int>.joinToRangedString(
    separator: CharSequence = ", ",
    rangeSeparator: CharSequence = "-",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    rangeLimit: Int = -1,
    truncated: CharSequence = "..."
): String = this.iterator().joinToRangedString(separator = separator, rangeSeparator = rangeSeparator, prefix = prefix, postfix = postfix, rangeLimit = rangeLimit, truncated = truncated)

fun Array<Int>.joinToRangedString(
    separator: CharSequence = ", ",
    rangeSeparator: CharSequence = "-",
    prefix: CharSequence = "",
    postfix: CharSequence = "",
    rangeLimit: Int = -1,
    truncated: CharSequence = "..."
): String = this.iterator().joinToRangedString(separator = separator, rangeSeparator = rangeSeparator, prefix = prefix, postfix = postfix, rangeLimit = rangeLimit, truncated = truncated)
