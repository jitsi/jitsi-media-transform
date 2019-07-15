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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class Notifier<HandlerType : Any> {
    private val handlers: MutableList<HandlerType> = CopyOnWriteArrayList()

    fun addHandler(handler: HandlerType) = handlers.add(handler)

    fun notifyAll(block: (HandlerType) -> Unit) {
        handlers.forEach(block)
    }
}

class MapNotifier<MapKeyType, MapValueType> {
    /**
     * Handlers which subscribe to the state of a specific key and are notified when any
     * change in value for that key occurs.  It's invoked for adds, updates and removed
     * (removed is implemented by passing a null value to the handler)
     */
    private val keyChangeHandlers: MutableMap<MapKeyType, MutableList<(MapValueType?) -> Unit>> = ConcurrentHashMap()
    /**
     * Handlers which subscribe to any change of state in the map and receive the event which
     * occurred.  The event includes a key, the key's new value and a copy of the current
     * state of the map.  If the key has been removed, the new value will be null
     */
    private val mapEventHandlers: MutableList<(MapKeyType, MapValueType?, Map<MapKeyType, MapValueType>) -> Unit> = CopyOnWriteArrayList()

    private fun notifyMapEvent(key: MapKeyType, newValue: MapValueType?, currentState: Map<MapKeyType, MapValueType>) {
        keyChangeHandlers[key]?.forEach { it(newValue) }
        mapEventHandlers.forEach {
            it(key, newValue, currentState)
        }
    }

    fun handleMapEvent(event: ObservableMapEvent<MapKeyType, MapValueType>) {
        when (event) {
            is ObservableMapEvent.EntryAdded<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                notifyMapEvent(
                    event.newEntry.key as MapKeyType,
                    event.newEntry.value as MapValueType,
                    event.currentState as Map<MapKeyType, MapValueType>
                )
            }
            is ObservableMapEvent.EntryUpdated<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                notifyMapEvent(
                    event.key as MapKeyType,
                    event.newValue as MapValueType,
                    event.currentState as Map<MapKeyType, MapValueType>
                )
            }
            is ObservableMapEvent.EntryRemoved<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                notifyMapEvent(
                    event.removedEntry.key as MapKeyType,
                    null as MapValueType,
                    event.currentState as Map<MapKeyType, MapValueType>
                )
            }
        }
    }

    /**
     * Subscribe to changes in value for a specific key (including the key
     * being removed entirely, which results in the handler being invoked
     * with 'null'
     */
    fun onKeyChange(keyValue: MapKeyType, handler: (MapValueType?) -> Unit) {
        keyChangeHandlers.getOrPut(keyValue, { mutableListOf() }).add(handler)
    }

    /**
     * Subscribe to any changes in the map.  The handler will be invoked with
     * the current state of the map.
     */
    fun onMapEvent(handler: (MapKeyType, MapValueType?, Map<MapKeyType, MapValueType>) -> Unit) {
        mapEventHandlers.add(handler)
    }
}