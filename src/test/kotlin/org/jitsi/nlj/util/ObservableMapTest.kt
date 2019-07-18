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
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.haveSize
import io.kotlintest.matchers.maps.shouldContainExactly
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.nlj.util.observable.map.MapEventHandler
import org.jitsi.nlj.util.observable.map.ObservableMap

class ObservableMapTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private data class MapEvent(
        val key: Int,
        val value: String?,
        val currentState: Map<Int, String>
    )
    /**
     * Track the map's events state change events by keeping a copy of
     * each event
     */
    private val mapEvents = mutableListOf<MapEvent>()

    private val map = ObservableMap<Int, String>().apply {
        onChange(object : MapEventHandler<Int, String> {
            override fun entryAdded(newEntry: Map.Entry<Int, String>, currentState: Map<Int, String>) {
                mapEvents.add(MapEvent(newEntry.key, newEntry.value, currentState))
            }

            override fun entryRemoved(removedEntry: Map.Entry<Int, String>, currentState: Map<Int, String>) {
                mapEvents.add(MapEvent(removedEntry.key, null, currentState))
            }

            override fun entryUpdated(updatedEntry: Map.Entry<Int, String>, currentState: Map<Int, String>) {
                mapEvents.add(MapEvent(updatedEntry.key, updatedEntry.value, currentState))
            }
        })
    }

    /**
     * Clear any prior key/state change events
     */
    private fun resetChangeHistory() {
        mapEvents.clear()
    }

    /**
     * Test setup often requires getting the map to a certain state so it can be further
     * manipulated and verified.  The verification should ignore any events that were
     * generated during that 'setup' phase.  This helper allows the setup to be done
     * and then automatically clears the event history.
     */
    private fun setup(block: ObservableMapTest.() -> Unit) {
        block()
        resetChangeHistory()
    }

    private fun expectStateChange(expectedKey: Int, expectedNewValue: String?, expectedNewState: Map<Int, String>) {
        mapEvents.last().apply {
            key shouldBe expectedKey
            value shouldBe expectedNewValue
            currentState shouldContainExactly expectedNewState
        }
    }

    init {
        "put" {
            map.put(1, "one") shouldBe null
            should("fire a state change event") {
                expectStateChange(1, "one", mapOf(1 to "one"))
            }
            "and then updating it" {
                map.put(1, "uno") shouldBe "one"
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
        }
        "putAll" {
            map.putAll(mapOf(
                1 to "one",
                2 to "two",
                3 to "three"
            ))
            should("fire multiple state change events") {
                mapEvents should haveSize(3)
                // We don't know the order in which they'll be added, so just verify
                // the final state
                mapEvents[2].currentState shouldContainExactly mapOf(1 to "one", 2 to "two", 3 to "three")
            }
            "and then clearing it" {
                map.clear()
                should("fire multiple state change events") {
                    mapEvents should haveSize(6)
                    // We don't know the order in which they'll be removed, so just
                    // verify it ended up empty
                    mapEvents.last().currentState shouldBe emptyMap()
                }
            }
        }
        "remove" {
            setup {
                map[1] = "one"
            }
            "a key which is in the map" {
                map.remove(1) shouldBe "one"
                should("fire a state change event") {
                    expectStateChange(1, null, emptyMap())
                }
            }
            "a key which wasn't in the map" {
                map.remove(2) shouldBe null
                should("not fire a state change event") {
                    mapEvents should haveSize(0)
                }
            }
        }
        "remove with a specific value" {
            setup {
                map[1] = "one"
            }
            "which does match the current value" {
                map.remove(1, "one") shouldBe true
                should("fire a state change event") {
                    expectStateChange(1, null, emptyMap())
                }
            }
            "which doesn't match the current value" {
                map.remove(1, "two") shouldBe false
                should("not fire a state change event") {
                    mapEvents should haveSize(0)
                }
            }
        }
        "compute" {
            "for a key not present in the map" {
                "where the compute result is non-null" {
                    map.compute(1) { key, oldValue -> "uno" } shouldBe "uno"
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "where the compute result is null" {
                    map.compute(1) { key, oldValue -> null } shouldBe null
                    should("not fire a state change event") {
                        mapEvents shouldHaveSize 0
                    }
                }
            }
            "for a key already present in the map" {
                setup {
                    map[1] = "one"
                }
                "where the compute result is non-null" {
                    map.compute(1) { key, oldValue -> "uno" } shouldBe "uno"
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "where the compute result is null" {
                    map.compute(1) { key, oldValue -> null } shouldBe null
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
            }
        }
        "computeIfAbsent" {
            "when the key is already in the map" {
                setup {
                    map[1] = "one"
                }
                map.computeIfAbsent(1) { key -> "uno" } shouldBe "one"
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
            "when the key isn't in the map" {
                map.computeIfAbsent(1) { key -> "uno" } shouldBe "uno"
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
        }
        "computeIfPresent" {
            "when the key is already in the map" {
                setup {
                    map[1] = "one"
                }
                "and the result of the compute function is non-null" {
                    map.computeIfPresent(1) { key, value -> "uno" } shouldBe "uno"
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "and the result of the compute function is null" {
                    map.computeIfPresent(1) { key, value -> null } shouldBe null
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
            }
            "when the key isn't in the map" {
                map.computeIfPresent(1) { key, value -> "uno" } shouldBe null
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
        }
        "merge" {
            "when the key isn't in the map" {
                map.merge(1, "one") { _, _ -> throw Exception("should not reach here") }
                should("fire a state change event") {
                    expectStateChange(1, "one", mapOf(1 to "one"))
                }
            }
            "when the key is in the map" {
                setup {
                    map[1] = "one"
                }
                "when the new value is null" {
                    map.merge(1, "uno") { _, _ -> null } shouldBe null
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
                "when the new value is non-null" {
                    map.merge(1, "uno") { oldValue, newValue -> newValue } shouldBe "uno"
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
            }
        }
        "putIfAbsent" {
            "when the key is present" {
                setup {
                    map[1] = "one"
                }
                map.putIfAbsent(1, "uno") shouldBe "one"
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
            "when the key is absent" {
                map.putIfAbsent(1, "uno") shouldBe null
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
        }
        "replace" {
            "when the key is present" {
                setup {
                    map[1] = "one"
                }
                map.replace(1, "uno") shouldBe "one"
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
            "when the key is absent" {
                map.replace(1, "uno") shouldBe null
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
        }
        "replace with a specific old value" {
            "when the key is present" {
                setup {
                    map[1] = "one"
                }
                "with a matching old value" {
                    map.replace(1, "one", "uno") shouldBe true
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "with a non-matching old value" {
                    map.replace(1, "uno", "one") shouldBe false
                    should("not fire a state change event") {
                        mapEvents shouldHaveSize 0
                    }
                }
            }
            "when the key is absent" {
                map.replace(1, "one", "uno") shouldBe false
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
        }
        "replaceAll" {
            setup {
                map[1] = "one"
                map[2] = "two"
            }
            map.replaceAll { key, value ->
                value + value
            }
            should("fire 2 state change events") {
                mapEvents shouldHaveSize 2
                // We don't know what order the replace's will happen it, so just
                // verify the final state
                mapEvents.last().currentState shouldBe mapOf(1 to "oneone", 2 to "twotwo")
            }
        }
    }
}