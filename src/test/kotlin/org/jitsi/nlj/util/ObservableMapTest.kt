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

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.maps.shouldContainAll
import io.kotlintest.matchers.maps.shouldContainExactly
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

class ObservableMapTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val map = ObservableMap<Int, String>()
    private var mostRecentMap = mapOf<Int, String>()
    private val defaultHandler = { currMap: Map<Int, String> ->
        mostRecentMap = currMap.toMap()
    }.also { map.onChange(it) }

    init {
        "adding to the map" {
            "via assignment" {
                map[1] = "one"
                should("fire the handler") {
                    mostRecentMap shouldContainExactly mapOf(1 to "one")
                }
                "and then clearing the map" {
                    map.clear()
                    should("fire the handler") {
                        mostRecentMap shouldBe emptyMap()
                    }
                }
            }
            "via putAll" {
                val expected = mapOf(1 to "one", 2 to "two")
                map.putAll(expected)
                should("fire the handler") {
                    mostRecentMap shouldContainAll expected
                }
                "and then removing a key" {
                    map.remove(1)
                    should("fire the handler") {
                        mostRecentMap shouldContainExactly mapOf(2 to "two")
                    }
                }
            }
            "via computeIfAbsent" {
                map.computeIfAbsent(1) { "one" }
                should("fire the handler") {
                    mostRecentMap shouldContainExactly mapOf(1 to "one")
                }
            }
        }
        "adding a handler" {
            "after data has been added" {
                map[1] = "one"
                should("get the current state") {
                    var newCurrMap = mapOf<Int, String>()
                    map.onChange { currMap ->
                        newCurrMap = currMap
                    }
                    newCurrMap shouldContainExactly mapOf(1 to "one")
                }
            }
        }
    }
}