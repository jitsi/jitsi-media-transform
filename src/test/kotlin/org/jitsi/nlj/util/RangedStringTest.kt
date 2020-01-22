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

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class RangedStringTest : ShouldSpec() {
    init {
        "formatting a single value" {
            val range = 1..1
            val string = range.joinToRangedString()
            should("be just that value") {
                string shouldBe "1"
            }
        }
        "formatting a consecutive sequence" {
            val range = 1..10
            val string = range.joinToRangedString()
            should("be a range") {
                string shouldBe "1-10"
            }
        }
        "formatting two disjoint sequences" {
            val range = setOf(1..10, 12..20).flatten()
            val string = range.joinToRangedString()
            should("be several ranges") {
                string shouldBe "1-10, 12-20"
            }
        }
        "formatting nonconsecutive values" {
            val range = 1..9 step 2
            val string = range.joinToRangedString()
            should("be discrete values") {
                string shouldBe "1, 3, 5, 7, 9"
            }
        }
        "formatting range combinations" {
            should("work if a single value is first") {
                val range = setOf(1..1, 3..10).flatten()
                val string = range.joinToRangedString()
                string shouldBe "1, 3-10"
            }
            should("work if a single value is last") {
                val range = setOf(1..8, 10..10).flatten()
                val string = range.joinToRangedString()
                string shouldBe "1-8, 10"
            }
        }
        "formatting with a prefix" {
            val range = 1..10
            val string = range.joinToRangedString(prefix = "AAA ")
            should("work") {
                string shouldBe "AAA 1-10"
            }
        }
        "formatting with a postfix" {
            val range = 1..10
            val string = range.joinToRangedString(postfix = " ZZZ")
            should("work") {
                string shouldBe "1-10 ZZZ"
            }
        }
        "formatting with a custom separator" {
            val range = 1..9 step 2
            val string = range.joinToRangedString(separator = "; ")
            should("work") {
                string shouldBe "1; 3; 5; 7; 9"
            }
        }
        "formatting with a custom range separator" {
            val range = setOf(1..10, 12..20).flatten()
            val string = range.joinToRangedString(rangeSeparator = "→")
            should("work") {
                string shouldBe "1→10, 12→20"
            }
        }
        "setting a range limit" {
            should("work") {
                val range = arrayOf(1, 2, 4, 5, 7, 8, 10, 11, 13, 14)
                val string = range.joinToRangedString(rangeLimit = 4)
                string shouldBe "1-2, 4-5, 7-8, 10-11, ..."
            }
            should("not print truncation for a short set of sequences") {
                val range = arrayOf(1, 2, 4, 5, 7, 8, 10, 11, 12, 13, 14)
                val string = range.joinToRangedString(rangeLimit = 4)
                string shouldBe "1-2, 4-5, 7-8, 10-14"
            }
        }
        "formatting with a custom truncated value" {
            val range = arrayOf(1, 2, 4, 5, 7, 8, 10, 11, 13, 14)
            val string = range.joinToRangedString(rangeLimit = 4, truncated = "etc.")
            should("work") {
                string shouldBe "1-2, 4-5, 7-8, 10-11, etc."
            }
        }
    }
}
