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

class ObservableMapTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val map = ObservableMap<Int, String>()
    /**
     * Track each key change event that's been fired by tracking a list of
     * pairs of the key to the value at the time of the event
     */
    private val keyChanges = mutableListOf<Pair<Int, String?>>()

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

    /**
     * Clear any prior key/state change events
     */
    private fun resetChangeHistory() {
        keyChanges.clear()
        mapEvents.clear()
    }
    private val mapNotifier = MapNotifier<Int, String>().also { it ->
        map.onChange(it::handleMapEvent)
        // We'll watch the key '1' by default
        it.onKeyChange(1) {
            keyChanges.add(1 to it)
        }
        it.onMapEvent { key, value, currentState ->
            mapEvents.add(MapEvent(key, value, currentState))
        }
    }

    private fun expectKeyChange(key: Int, expectedNewValue: String?) {
        keyChanges.last() shouldBe (key to expectedNewValue)
    }

    private fun expectStateChange(expectedKey: Int, expectedNewValue: String?, expectedNewState: Map<Int, String>) {
        mapEvents.last().apply {
            key shouldBe expectedKey
            value shouldBe expectedNewValue
            currentState shouldContainExactly expectedNewState
        }
    }

    init {
        "assigning map[key] = value" {
            "for a key which has a handler" {
                map.put(1, "one") shouldBe null
                should("fire a key change event") {
                    expectKeyChange(1, "one")
                }
                should("fire a state change event") {
                    expectStateChange(1, "one", mapOf(1 to "one"))
                }
                "and then updating it" {
                    map.put(1, "uno") shouldBe "one"
                    should("fire a key change event") {
                        expectKeyChange(1, "uno")
                    }
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "and then removing it" {
                    map.remove(1) shouldBe "one"
                    should("fire a key change event") {
                        expectKeyChange(1, null)
                    }
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
                "and then trying to remove it using a specific value" {
                    "which does match the current value" {
                        map.remove(1, "one") shouldBe true
                        should("fire a key change event") {
                            expectKeyChange(1, null)
                        }
                        should("fire a state change event") {
                            expectStateChange(1, null, emptyMap())
                        }
                    }
                    "which doesn't match the current value" {
                        map.remove(1, "two") shouldBe false
                        should("not fire a key change event") {
                            keyChanges should haveSize(1)
                        }
                        should("not fire a state change event") {
                            mapEvents should haveSize(1)
                        }
                    }
                }
            }
            "for a key without a handler" {
                map.put(2, "two") shouldBe null
                should("not fire a key change event") {
                    keyChanges shouldHaveSize 0
                }
                should("fire a state change event") {
                    expectStateChange(2, "two", mapOf(2 to "two"))
                }
            }
        }
        "adding entries via putAll" {
            map.putAll(mapOf(
                1 to "one",
                2 to "two",
                3 to "three"
            ))
            should("fire a key change event") {
                expectKeyChange(1, "one")
            }
            should("fire multiple state change events") {
                mapEvents should haveSize(3)
                mapEvents[0].currentState shouldContainExactly mapOf(1 to "one")
                mapEvents[1].currentState shouldContainExactly mapOf(1 to "one", 2 to "two")
                mapEvents[2].currentState shouldContainExactly mapOf(1 to "one", 2 to "two", 3 to "three")
            }
            "and then clearing it" {
                map.clear()
                should("fire a key change event") {
                    expectKeyChange(1, null)
                }
                should("fire multiple state change events") {
                    mapEvents should haveSize(6)
                    // We don't know the order in which they'll be removed, so just
                    // verify it ended up empty
                    mapEvents.last().currentState shouldBe emptyMap()
                }
            }
        }
        "compute" {
            "for a key not present in the map" {
                "where the compute result is non-null" {
                    map.compute(1) { key, oldValue -> "uno" } shouldBe "uno"
                    should("fire a key change event") {
                        expectKeyChange(1, "uno")
                    }
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "where the compute result is null" {
                    map.compute(1) { key, oldValue -> null } shouldBe null
                    should("not fire a key change event") {
                        keyChanges shouldHaveSize 0
                    }
                    should("not fire a state change event") {
                        mapEvents shouldHaveSize 0
                    }
                }
            }
            "for a key already present in the map" {
                map[1] = "one"
                resetChangeHistory()
                "where the compute result is non-null" {
                    map.compute(1) { key, oldValue -> "uno" } shouldBe "uno"
                    should("fire a key change event") {
                        expectKeyChange(1, "uno")
                    }
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "where the compute result is null" {
                    map.compute(1) { key, oldValue -> null } shouldBe null
                    should("fire a key change event") {
                        expectKeyChange(1, null)
                    }
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
            }
        }
        "computeIfAbsent" {
            "when the key is already in the map" {
                map[1] = "one"
                resetChangeHistory()

                map.computeIfAbsent(1) { key -> "uno" } shouldBe "one"
                should("not fire a key change event") {
                    keyChanges shouldHaveSize 0
                }
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
            "when the key isn't in the map" {
                map.computeIfAbsent(1) { key -> "uno" } shouldBe "uno"
                should("fire a key change event") {
                    expectKeyChange(1, "uno")
                }
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
        }
        "computeIfPresent" {
            "when the key is already in the map" {
                map[1] = "one"
                resetChangeHistory()
                "and the result of the compute function is non-null" {
                    map.computeIfPresent(1) { key, value -> "uno" } shouldBe "uno"
                    should("fire a key change event") {
                        expectKeyChange(1, "uno")
                    }
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "and the result of the compute function is null" {
                    map.computeIfPresent(1) { key, value -> null } shouldBe null
                    should("fire a key change event") {
                        expectKeyChange(1, null)
                    }
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
            }
            "when the key isn't in the map" {
                map.computeIfPresent(1) { key, value -> "uno" } shouldBe null
                should("not fire a key change event") {
                    keyChanges shouldHaveSize 0
                }
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
        }
        "merge" {
            "when the key isn't in the map" {
                resetChangeHistory()
                map.merge(1, "one") { _, _ -> throw Exception("should not reach here") }
                should("fire a key change event") {
                    expectKeyChange(1, "one")
                }
                should("fire a state change event") {
                    expectStateChange(1, "one", mapOf(1 to "one"))
                }
            }
            "when the key is in the map" {
                map[1] = "one"
                resetChangeHistory()
                "when the new value is null" {
                    map.merge(1, "uno") { _, _ -> null } shouldBe null
                    should("fire a key change event") {
                        expectKeyChange(1, null)
                    }
                    should("fire a state change event") {
                        expectStateChange(1, null, emptyMap())
                    }
                }
                "when the new value is non-null" {
                    map.merge(1, "uno") { oldValue, newValue -> newValue } shouldBe "uno"
                    should("fire a key change event") {
                        expectKeyChange(1, "uno")
                    }
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
            }
        }
        "putIfAbsent" {
            "when the key is present" {
                map[1] = "one"
                resetChangeHistory()
                map.putIfAbsent(1, "uno") shouldBe "one"
                should("not fire a key change event") {
                    keyChanges shouldHaveSize 0
                }
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
            "when the key is absent" {
                map.putIfAbsent(1, "uno") shouldBe null
                should("fire a key change event") {
                    expectKeyChange(1, "uno")
                }
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
        }
        "replace" {
            "when the key is present" {
                map[1] = "one"
                resetChangeHistory()
                map.replace(1, "uno") shouldBe "one"
                should("fire a key change event") {
                    expectKeyChange(1, "uno")
                }
                should("fire a state change event") {
                    expectStateChange(1, "uno", mapOf(1 to "uno"))
                }
            }
            "when the key is absent" {
                map.replace(1, "uno") shouldBe null
                should("not fire a key change event") {
                    keyChanges shouldHaveSize 0
                }
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
        }
        "replace with a specific old value" {
            "when the key is present" {
                map[1] = "one"
                resetChangeHistory()
                "with a matching old value" {
                    map.replace(1, "one", "uno") shouldBe true
                    should("fire a key change event") {
                        expectKeyChange(1, "uno")
                    }
                    should("fire a state change event") {
                        expectStateChange(1, "uno", mapOf(1 to "uno"))
                    }
                }
                "with a non-matching old value" {
                    map.replace(1, "uno", "one") shouldBe false
                    should("not fire a key change event") {
                        keyChanges shouldHaveSize 0
                    }
                    should("not fire a state change event") {
                        mapEvents shouldHaveSize 0
                    }
                }
            }
            "when the key is absent" {
                map.replace(1, "one", "uno") shouldBe false
                should("not fire a key change event") {
                    keyChanges shouldHaveSize 0
                }
                should("not fire a state change event") {
                    mapEvents shouldHaveSize 0
                }
            }
        }
        "replaceAll" {
            map[1] = "one"
            map[2] = "two"
            resetChangeHistory()
            map.replaceAll { key, value ->
                value + value
            }
            should("fire one key change event") {
                keyChanges shouldHaveSize 1
                expectKeyChange(1, "oneone")
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