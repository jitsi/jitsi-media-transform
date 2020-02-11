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
package org.jitsi.nlj.stats

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.types.shouldBeInstanceOf
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.util.OrderedJsonObject

class DelayStatsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "adding stats" {
            val delayStats = DelayStats()
            repeat(100) { delayStats.addDelay(1) }
            repeat(100) { delayStats.addDelay(5) }

            should("calculate the average correctly") {
                val json = delayStats.toJson()
                json.get("average_delay_ms") shouldBe 3.0
            }

            should("calculate the max correctly") {
                delayStats.addDelay(100)
                val json = delayStats.toJson()
                json.get("max_delay_ms") shouldBe 100
            }

            should("calculate the buckets correctly") {
                delayStats.addDelay(150)
                delayStats.addDelay(1500)
                val bucketsJson = delayStats.toJson().get("buckets")
                bucketsJson.shouldBeInstanceOf<OrderedJsonObject>()

                bucketsJson as OrderedJsonObject
                bucketsJson["< 2 ms"] shouldBe 100
                bucketsJson["< 5 ms"] shouldBe 100
                bucketsJson["< 200 ms"] shouldBe 1
                bucketsJson["> 1000 ms"] shouldBe 1
            }
        }
    }
}
