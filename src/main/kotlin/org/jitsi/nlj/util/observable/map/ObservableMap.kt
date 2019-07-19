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

package org.jitsi.nlj.util.observable.map

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiFunction
import java.util.function.Function

interface MapEventHandler<T, U> {
    fun entryAdded(newEntry: Map.Entry<T, U>, currentState: Map<T, U>)
    fun entryUpdated(updatedEntry: Map.Entry<T, U>, currentState: Map<T, U>)
    fun entryRemoved(removedEntry: Map.Entry<T, U>, currentState: Map<T, U>)
}

private class MyEntry<T, U>(
    override val key: T,
    override val value: U
) : Map.Entry<T, U>

/**
 * A [MutableMap] which will notify observers (installed via [onChange]) of any changes to the map
 * by invoking the given handler with the current state of the map.  It should be noted that some
 * operations of this Map may be implemented in less efficient methods in order to make
 * observability simpler.  For example, [ObservableMap.clear] is implemented by removing each
 * entry one by one.
 *
 * NOTE: It was considered to invoke the handlers outside of the lock, but doing so could
 * result in duplicate/out-of-order events being fired to the handler, so I kept them inside the lock.
 */
class ObservableMap<T, U>(private val data: MutableMap<T, U> = mutableMapOf()) : MutableMap<T, U> by data {
    private val handlers: MutableList<MapEventHandler<T, U>> = CopyOnWriteArrayList()
    /**
     * Lock used to make all modifications to the map and notification of handlers
     * atomic
     */
    private val lock = Any()

    fun onChange(handler: MapEventHandler<T, U>) {
        handlers.add(handler)
    }

    private fun notifyAdded(key: T, newValue: U) {
        val dataCopy = data.toMap()
        handlers.forEach { it.entryAdded(MyEntry(key, newValue), dataCopy) }
    }

    private fun notifyUpdated(key: T, newValue: U) {
        val dataCopy = data.toMap()
        handlers.forEach { it.entryUpdated(MyEntry(key, newValue), dataCopy) }
    }

    private fun notifyRemoved(key: T, value: U) {
        val dataCopy = data.toMap()
        handlers.forEach { it.entryRemoved(MyEntry(key, value), dataCopy) }
    }

    override fun put(key: T, value: U): U? = synchronized(lock) {
        return data.put(key, value).also {
            if (it == null) {
                notifyAdded(key, value)
            } else if (it != value) {
                notifyUpdated(key, value)
            }
        }
    }

    override fun putAll(from: Map<out T, U>) = synchronized(lock) {
        // We implement this as an aggregate of [put] operations so
        // we can properly fire [EntryAdded] or [EntryUpdated] events
        from.forEach { (key, value) ->
            put(key, value)
        }
    }

    override fun remove(key: T): U? = synchronized(lock) {
        return data.remove(key).also {
            if (it != null) {
                notifyRemoved(key, it)
            }
        }
    }

    override fun remove(key: T, value: U): Boolean = synchronized(lock) {
        return data.remove(key, value).also { removed ->
            if (removed) {
                notifyRemoved(key, value)
            }
        }
    }

    override fun clear() = synchronized(lock) {
        val iter = data.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            iter.remove()
            notifyRemoved(entry.key, entry.value)
        }
    }

    override fun compute(key: T, remappingFunction: BiFunction<in T, in U?, out U?>): U? = synchronized(lock) {
        val oldValue = data[key]
        val newValue = remappingFunction.apply(key, oldValue)
        return if (oldValue != null) {
            if (newValue != null) {
                put(key, newValue)
                newValue
            } else {
                remove(key)
                null
            }
        } else {
            if (newValue != null) {
                put(key, newValue)
                newValue
            } else {
                null
            }
        }
    }

    override fun computeIfAbsent(key: T, mappingFunction: Function<in T, out U>): U = synchronized(lock) {
        val currentValue = data[key]
        return if (currentValue == null) {
            val newValue = mappingFunction.apply(key)
            if (newValue != null) {
                put(key, newValue)
            }
            // If the new value is null, we won't add it but will return the null value to denote
            // nothing was added
            newValue
        } else {
            currentValue
        }
    }

    override fun computeIfPresent(key: T, remappingFunction: BiFunction<in T, in U, out U?>): U? = synchronized(lock) {
        val oldValue = data[key]
        return if (oldValue != null) {
            val newValue = remappingFunction.apply(key, oldValue)
            if (newValue != null) {
                put(key, newValue)
                newValue
            } else {
                remove(key)
                null
            }
        } else {
            null
        }
    }

    override fun merge(key: T, value: U, remappingFunction: BiFunction<in U, in U, out U?>): U? = synchronized(lock) {
        val oldValue = data[key]
        val newValue = if (oldValue == null) value else remappingFunction.apply(oldValue, value)
        if (newValue == null) {
            remove(key)
        } else {
            put(key, newValue)
        }
        return newValue
    }

    override fun putIfAbsent(key: T, value: U): U? = synchronized(lock) {
        return data.putIfAbsent(key, value).also {
            if (it == null) {
                notifyAdded(key, value)
            }
        }
    }

    override fun replace(key: T, value: U): U? = synchronized(lock) {
        return data.replace(key, value).also {
            if (it != null) {
                notifyUpdated(key, value)
            }
        }
    }

    override fun replace(key: T, oldValue: U, newValue: U): Boolean = synchronized(lock) {
        return data.replace(key, oldValue, newValue).also { replaced ->
            if (replaced) {
                notifyUpdated(key, newValue)
            }
        }
    }

    override fun replaceAll(function: BiFunction<in T, in U, out U>) = synchronized(lock) {
        // We manually walk the map and apply the function via multiple calls to
        // put so we can get the proper events for each one
        data.forEach { (key, value) ->
            put(key, function.apply(key, value))
        }
    }
}