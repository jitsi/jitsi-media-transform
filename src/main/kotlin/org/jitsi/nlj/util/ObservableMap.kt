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

import org.jitsi.nlj.util.ObservableMapEvent.EntryUpdated
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.BiFunction
import java.util.function.Function

/**
 * Note: we use a single [EntryUpdated] class to handle adds, updates and removals.  This is
 * because some cases (e.g. [ObservableMap.computeIfAbsent]) make it hard to detect whether
 * or not an entry already existed (at least, not without implementing the computeIfAbsent
 * logic manually here).
 *
 * Technically this makes it impossible to distinguish the difference between a map which
 * has an entry for a given key but with a null value and a map without an entry for a given
 * key at all, but as of now this distinction is not important to us.
 */

// TODO: do we really need the current state in any of these events?  maybe we can just have 'updated'
// and implement 'clear' as a bunch of removes?  that is only awkward if we push the full state every
// time, but if we don't need that then it wouldn't be so bad?
// --> some things like hasPliSupport would be trickier if we just had add and remove (it would have to
// track WHICH pts denoted pli support so it could know to set it to false (we could, instead, keep track
// of all the payload types which added pli support, and add/remove to the set as things updated?)
// -->
sealed class ObservableMapEvent<T, U> {
    class EntryAdded<T, U>(val newEntry: Map.Entry<T, U>, val currentState: Map<T, U>) : ObservableMapEvent<T, U>()
    class EntryUpdated<T, U>(val key: T, val newValue: U?, val currentState: Map<T, U>) : ObservableMapEvent<T, U>()
    class EntryRemoved<T, U>(val removedEntry: Map.Entry<T, U>, val currentState: Map<T, U>) : ObservableMapEvent<T, U>()
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
 * result in duplicate events being fired to the handler, so I kept them inside the lock.
 */
class ObservableMap<T, U>(private val data: MutableMap<T, U> = mutableMapOf()) : MutableMap<T, U> by data {
    private val handlers: MutableList<(ObservableMapEvent<T, U>) -> Unit> = CopyOnWriteArrayList()
    private val lock = Any()

    fun onChange(handler: (ObservableMapEvent<T, U>) -> Unit) {
        handlers.add(handler)
    }

    private fun notifyHandlers(event: ObservableMapEvent<T, U>) {
        handlers.forEach { it(event) }
    }

    private fun notifyAdded(key: T, newValue: U) {
        notifyHandlers(ObservableMapEvent.EntryAdded(MyEntry(key, newValue), data.toMap()))
    }

    private fun notifyUpdated(key: T, newValue: U?) {
        notifyHandlers(ObservableMapEvent.EntryUpdated(key, newValue, data.toMap()))
    }

    private fun notifyRemoved(key: T, value: U) {
        notifyHandlers(ObservableMapEvent.EntryRemoved(MyEntry(key, value), data.toMap()))
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
        val oldValue = data.get(key)
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
        if (oldValue != null) {
            val newValue = remappingFunction.apply(key, oldValue)
            if (newValue != null) {
                put(key, newValue)
                return newValue
            } else {
                remove(key)
                return null
            }
        } else {
            return null
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