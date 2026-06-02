/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.ahoo.wow.messaging

import me.ahoo.wow.api.messaging.Header

/**
 * Default implementation of the [Header] interface.
 *
 * This class provides a mutable header implementation that can be made read-only.
 * It delegates map operations to an internal mutable map while enforcing read-only constraints.
 *
 * @property delegate The underlying mutable map that stores header key-value pairs
 * @property isReadOnly Whether this header is read-only (volatile for thread safety)
 * @author ahoo wang
 */
class DefaultHeader(
    private val delegate: MutableMap<String, String> = mutableMapOf(),
    @Volatile
    override var isReadOnly: Boolean = false
) : Header,
    MutableMap<String, String> by delegate {
    companion object {
        /**
         * Creates an empty header instance.
         *
         * @return A new empty [Header] instance
         */
        fun empty(): Header = DefaultHeader()
    }

    private val guardedEntries = GuardedMutableEntrySet(delegate.entries, ::checkWritable)
    private val guardedKeys = GuardedMutableSet(delegate.keys, ::checkWritable)
    private val guardedValues = GuardedMutableCollection(delegate.values, ::checkWritable)

    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = guardedEntries

    override val keys: MutableSet<String>
        get() = guardedKeys

    override val values: MutableCollection<String>
        get() = guardedValues

    /**
     * Makes this header read-only and returns it.
     *
     * After calling this method, any attempts to modify the header will throw
     * an [UnsupportedOperationException].
     *
     * @return This header instance, now read-only
     */
    override fun withReadOnly(): Header {
        isReadOnly = true
        return this
    }

    /**
     * Creates a copy of this header.
     *
     * The copy is mutable and not read-only, regardless of the original's state.
     *
     * @return A new mutable copy of this header
     */
    override fun copy(): Header = empty().with(this)

    /**
     * Executes a write operation if the header is not read-only.
     *
     * @param block The block of code to execute for the write operation
     * @return The result of the block execution
     * @throws UnsupportedOperationException if the header is read-only
     */
    private fun checkWritable() {
        if (isReadOnly) {
            throw UnsupportedOperationException("Header is read only.")
        }
    }

    private inline fun <T> write(block: () -> T): T {
        checkWritable()
        return block()
    }

    /**
     * Associates the specified value with the specified key in this header.
     *
     * @param key The key with which the specified value is to be associated
     * @param value The value to be associated with the specified key
     * @return The previous value associated with the key, or null if there was no mapping
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun put(
        key: String,
        value: String
    ): String? =
        write {
            delegate.put(key, value)
        }

    /**
     * Removes the mapping for the specified key from this header if present.
     *
     * @param key The key whose mapping is to be removed
     * @return The previous value associated with the key, or null if there was no mapping
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun remove(key: String): String? =
        write {
            delegate.remove(key)
        }

    /**
     * Removes the entry for the specified key only if it is currently mapped to the specified value.
     *
     * @param key The key whose mapping is to be removed
     * @param value The value expected to be associated with the key
     * @return true if the value was removed, false otherwise
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun remove(
        key: String,
        value: String
    ): Boolean =
        write {
            delegate.remove(key, value)
        }

    /**
     * Copies all of the mappings from the specified map to this header.
     *
     * @param from Mappings to be stored in this header
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun putAll(from: Map<out String, String>) {
        write {
            delegate.putAll(from)
        }
    }

    /**
     * Removes all mappings from this header.
     *
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun clear() {
        write {
            delegate.clear()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Map<*, *>) return false
        return delegate == other
    }

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = "DefaultHeader(delegate=$delegate)"

    private open class GuardedMutableCollection<T>(
        private val delegate: MutableCollection<T>,
        private val checkWritable: () -> Unit
    ) : MutableCollection<T> {
        override val size: Int
            get() = delegate.size

        override fun add(element: T): Boolean {
            checkWritable()
            return delegate.add(element)
        }

        override fun addAll(elements: Collection<T>): Boolean {
            checkWritable()
            return delegate.addAll(elements)
        }

        override fun clear() {
            checkWritable()
            delegate.clear()
        }

        override fun contains(element: T): Boolean = delegate.contains(element)

        override fun containsAll(elements: Collection<T>): Boolean = delegate.containsAll(elements)

        override fun isEmpty(): Boolean = delegate.isEmpty()

        override fun iterator(): MutableIterator<T> =
            GuardedMutableIterator(delegate.iterator(), checkWritable) { it }

        override fun remove(element: T): Boolean {
            checkWritable()
            return delegate.remove(element)
        }

        override fun removeAll(elements: Collection<T>): Boolean {
            checkWritable()
            return delegate.removeAll(elements)
        }

        override fun retainAll(elements: Collection<T>): Boolean {
            checkWritable()
            return delegate.retainAll(elements)
        }
    }

    private class GuardedMutableSet<T>(
        delegate: MutableSet<T>,
        checkWritable: () -> Unit
    ) : GuardedMutableCollection<T>(delegate, checkWritable),
        MutableSet<T>

    private class GuardedMutableEntrySet(
        private val delegate: MutableSet<MutableMap.MutableEntry<String, String>>,
        private val checkWritable: () -> Unit
    ) : MutableSet<MutableMap.MutableEntry<String, String>> {
        override val size: Int
            get() = delegate.size

        override fun add(element: MutableMap.MutableEntry<String, String>): Boolean {
            checkWritable()
            return delegate.add(element)
        }

        override fun addAll(elements: Collection<MutableMap.MutableEntry<String, String>>): Boolean {
            checkWritable()
            return delegate.addAll(elements)
        }

        override fun clear() {
            checkWritable()
            delegate.clear()
        }

        override fun contains(element: MutableMap.MutableEntry<String, String>): Boolean = delegate.contains(element)

        override fun containsAll(elements: Collection<MutableMap.MutableEntry<String, String>>): Boolean =
            delegate.containsAll(elements)

        override fun isEmpty(): Boolean = delegate.isEmpty()

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> =
            GuardedMutableIterator(delegate.iterator(), checkWritable) {
                GuardedMutableEntry(it, checkWritable)
            }

        override fun remove(element: MutableMap.MutableEntry<String, String>): Boolean {
            checkWritable()
            return delegate.remove(element)
        }

        override fun removeAll(elements: Collection<MutableMap.MutableEntry<String, String>>): Boolean {
            checkWritable()
            return delegate.removeAll(elements)
        }

        override fun retainAll(elements: Collection<MutableMap.MutableEntry<String, String>>): Boolean {
            checkWritable()
            return delegate.retainAll(elements)
        }
    }

    private class GuardedMutableIterator<T>(
        private val delegate: MutableIterator<T>,
        private val checkWritable: () -> Unit,
        private val wrap: (T) -> T
    ) : MutableIterator<T> {
        override fun hasNext(): Boolean = delegate.hasNext()

        override fun next(): T = wrap(delegate.next())

        override fun remove() {
            checkWritable()
            delegate.remove()
        }
    }

    private class GuardedMutableEntry(
        private val delegate: MutableMap.MutableEntry<String, String>,
        private val checkWritable: () -> Unit
    ) : MutableMap.MutableEntry<String, String> {
        override val key: String
            get() = delegate.key
        override val value: String
            get() = delegate.value

        override fun setValue(newValue: String): String {
            checkWritable()
            return delegate.setValue(newValue)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Map.Entry<*, *>) {
                return false
            }
            return key == other.key && value == other.value
        }

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()

        override fun toString(): String = "$key=$value"
    }
}

/**
 * Converts a nullable map of strings to a [Header] instance.
 *
 * If the map is null or empty, returns an empty header.
 * If the map is already a Header, returns it as-is.
 * Otherwise, creates a new DefaultHeader with a copy of the map.
 *
 * @receiver The map to convert, can be null
 * @return A Header instance representing the map
 */
fun Map<String, String>?.toHeader(): Header {
    if (isNullOrEmpty()) {
        return DefaultHeader.empty()
    }
    if (this is Header) {
        return this
    }
    return DefaultHeader(this.toMutableMap())
}
