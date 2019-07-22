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

/**
 * Filters events from an [ObservableMap] except for those whose
 * key pass the given predicate.
 */
abstract class MapEventKeyFilterHandler<T, U>(
    val keyPredicate: (T) -> Boolean
) {
    val mapEventHandler: MapEventHandler<T, U> = this.MapHandler()

    abstract fun keyAdded(key: T, value: U)
    abstract fun keyRemoved(key: T)
    abstract fun keyUpdated(key: T, newValue: U)

    private inner class MapHandler : MapEventHandler<T, U> {
        override fun entryAdded(newEntry: Map.Entry<T, U>, currentState: Map<T, U>) {
            if (keyPredicate(newEntry.key)) {
                keyAdded(newEntry.key, newEntry.value)
            }
        }

        override fun entryUpdated(updatedEntry: Map.Entry<T, U>, currentState: Map<T, U>) {
            if (keyPredicate(updatedEntry.key)) {
                keyUpdated(updatedEntry.key, updatedEntry.value)
            }
        }

        override fun entryRemoved(removedEntry: Map.Entry<T, U>, currentState: Map<T, U>) {
            if (keyPredicate(removedEntry.key)) {
                keyRemoved(removedEntry.key)
            }
        }
    }
}

/**
 * Filters events from an [ObservableMap] except for those whose
 * value pass the given predicate.
 */
abstract class MapEventValueFilterHandler<T, U>(
    val valuePredicate: (U) -> Boolean
) {
    val mapEventHandler: MapEventHandler<T, U> = this.MapHandler()

    abstract fun entryAdded(key: T, value: U)
    abstract fun entryRemoved(key: T)
    abstract fun entryUpdated(key: T, newValue: U)

    private inner class MapHandler : MapEventHandler<T, U> {
        override fun entryAdded(newEntry: Map.Entry<T, U>, currentState: Map<T, U>) {
            if (valuePredicate(newEntry.value)) {
                entryAdded(newEntry.key, newEntry.value)
            }
        }

        override fun entryUpdated(updatedEntry: Map.Entry<T, U>, currentState: Map<T, U>) {
            if (valuePredicate(updatedEntry.value)) {
                entryUpdated(updatedEntry.key, updatedEntry.value)
            }
        }

        override fun entryRemoved(removedEntry: Map.Entry<T, U>, currentState: Map<T, U>) {
            if (valuePredicate(removedEntry.value)) {
                entryRemoved(removedEntry.key)
            }
        }
    }
}
