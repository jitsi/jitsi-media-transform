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

import java.util.function.BiFunction
import java.util.function.Function

/**
 * A [MutableMap] which will notify observers (installed via [onChange]) of any changes to the map
 * by invoking the given handler with the current state of the map.
 *
 * NOTE: it was considered to invoke the handlers outside of the lock, but doing so could
 * result in duplicate events being fired to the handler, so I kept them inside the lock.
 */
class ObservableMap<T, U>(private val data: MutableMap<T, U> = mutableMapOf()) : MutableMap<T, U> by data {
    private val notifier = Notifier<(Map<T, U>) -> Unit>()
    private val lock = Any()

    fun onChange(handler: (Map<T, U>) -> Unit) = notifier.addHandler(handler).also { handler(data) }

    private fun notifyHandlers() {
        notifier.notifyAll { it(data) }
    }

    override fun put(key: T, value: U): U? = synchronized(lock) {
        return data.put(key, value).also { notifyHandlers() }
    }

    override fun putAll(from: Map<out T, U>) = synchronized(lock) {
        data.putAll(from).also { notifyHandlers() }
    }

    override fun remove(key: T): U? = synchronized(lock) {
        return data.remove(key).also { notifyHandlers() }
    }

    override fun remove(key: T, value: U): Boolean = synchronized(lock) {
        return data.remove(key, value).also { notifyHandlers() }
    }

    override fun clear() = synchronized(lock) {
        data.clear().also { notifyHandlers() }
    }

    override fun compute(key: T, remappingFunction: BiFunction<in T, in U?, out U?>): U? = synchronized(lock) {
        return data.compute(key, remappingFunction).also { notifyHandlers() }
    }

    override fun computeIfAbsent(key: T, mappingFunction: Function<in T, out U>): U = synchronized(lock) {
        return data.computeIfAbsent(key, mappingFunction).also { notifyHandlers() }
    }

    override fun computeIfPresent(key: T, remappingFunction: BiFunction<in T, in U, out U?>): U? = synchronized(lock) {
        return data.computeIfPresent(key, remappingFunction).also { notifyHandlers() }
    }

    override fun merge(key: T, value: U, remappingFunction: BiFunction<in U, in U, out U?>): U? = synchronized(lock) {
        return data.merge(key, value, remappingFunction).also { notifyHandlers() }
    }

    override fun putIfAbsent(key: T, value: U): U? = synchronized(lock) {
        return data.putIfAbsent(key, value).also { notifyHandlers() }
    }

    override fun replace(key: T, value: U): U? = synchronized(lock) {
        return data.replace(key, value).also { notifyHandlers() }
    }

    override fun replace(key: T, oldValue: U, newValue: U): Boolean = synchronized(lock) {
        return data.replace(key, oldValue, newValue).also { notifyHandlers() }
    }

    override fun replaceAll(function: BiFunction<in T, in U, out U>) = synchronized(lock) {
        data.replaceAll(function).also { notifyHandlers() }
    }
}