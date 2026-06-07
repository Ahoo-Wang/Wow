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

private const val DEFAULT_HEADER_CAPACITY = 4

private fun newHeaderMap(expectedSize: Int = 0): SmallHeaderMap = SmallHeaderMap(expectedSize)

private class SmallHeaderMap(expectedSize: Int = 0) : AbstractMutableMap<String, String>() {
    private var slots: Array<String?> = if (expectedSize == 0) {
        EMPTY
    } else {
        arrayOfNulls(slotSize(capacity(expectedSize)))
    }
    private var entryCount: Int = 0
    private var shared: Boolean = false

    override val size: Int
        get() = entryCount

    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = EntrySet()

    override fun containsKey(key: String): Boolean = indexOfKey(key) >= 0

    override fun containsValue(value: String): Boolean {
        for (index in 0 until entryCount) {
            if (slots[valueIndex(index)] == value) {
                return true
            }
        }
        return false
    }

    override fun get(key: String): String? {
        val index = indexOfKey(key)
        if (index < 0) {
            return null
        }
        return slots[valueIndex(index)]
    }

    override fun isEmpty(): Boolean = entryCount == 0

    override fun put(
        key: String,
        value: String
    ): String? {
        val index = indexOfKey(key)
        if (index >= 0) {
            val previous = slots[valueIndex(index)]
            ensureMutable(entryCount)
            slots[valueIndex(index)] = value
            return previous
        }
        ensureMutable(entryCount + 1)
        slots[keyIndex(entryCount)] = key
        slots[valueIndex(entryCount)] = value
        entryCount++
        return null
    }

    override fun remove(key: String): String? {
        val index = indexOfKey(key)
        if (index < 0) {
            return null
        }
        return removeAt(index)
    }

    override fun clear() {
        if (entryCount == 0) {
            return
        }
        if (shared) {
            slots = EMPTY
            entryCount = 0
            shared = false
            return
        }
        slots.fill(null, 0, slotSize(entryCount))
        entryCount = 0
    }

    fun copyMap(): SmallHeaderMap {
        val copied = SmallHeaderMap()
        if (entryCount > 0) {
            shared = true
            copied.shared = true
            copied.slots = slots
            copied.entryCount = entryCount
        }
        return copied
    }

    private fun indexOfKey(key: String): Int {
        for (index in 0 until entryCount) {
            if (slots[keyIndex(index)] == key) {
                return index
            }
        }
        return -1
    }

    private fun ensureMutable(requiredCapacity: Int) {
        val requiredSlotSize = slotSize(requiredCapacity)
        if (!shared && slots.size >= requiredSlotSize) {
            return
        }
        val newCapacity = when {
            slots.isEmpty() -> DEFAULT_HEADER_CAPACITY
            slots.size < requiredSlotSize -> capacity(slots.size / ENTRY_WIDTH) * 2
            else -> slots.size / ENTRY_WIDTH
        }.coerceAtLeast(requiredCapacity)
        slots = slots.copyOf(slotSize(newCapacity))
        shared = false
    }

    private fun removeAt(index: Int): String {
        val previous = checkNotNull(slots[valueIndex(index)])
        ensureMutable(entryCount)
        val lastIndex = entryCount - 1
        if (index < lastIndex) {
            slots.copyInto(
                slots,
                destinationOffset = keyIndex(index),
                startIndex = keyIndex(index + 1),
                endIndex = slotSize(entryCount),
            )
        }
        slots[keyIndex(lastIndex)] = null
        slots[valueIndex(lastIndex)] = null
        entryCount = lastIndex
        return previous
    }

    private inner class EntrySet : AbstractMutableSet<MutableMap.MutableEntry<String, String>>() {
        override val size: Int
            get() = entryCount

        override fun add(element: MutableMap.MutableEntry<String, String>): Boolean {
            throw UnsupportedOperationException("Add is not supported on map entries.")
        }

        override fun clear() = this@SmallHeaderMap.clear()

        override fun contains(element: MutableMap.MutableEntry<String, String>): Boolean {
            val index = indexOfKey(element.key)
            return index >= 0 && slots[valueIndex(index)] == element.value
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> = EntryIterator()

        override fun remove(element: MutableMap.MutableEntry<String, String>): Boolean {
            val index = indexOfKey(element.key)
            if (index < 0 || slots[valueIndex(index)] != element.value) {
                return false
            }
            removeAt(index)
            return true
        }
    }

    private inner class EntryIterator : MutableIterator<MutableMap.MutableEntry<String, String>> {
        private var nextIndex: Int = 0
        private var lastIndex: Int = -1

        override fun hasNext(): Boolean = nextIndex < entryCount

        override fun next(): MutableMap.MutableEntry<String, String> {
            if (!hasNext()) {
                throw NoSuchElementException()
            }
            val entry = Entry(nextIndex)
            lastIndex = nextIndex
            nextIndex++
            return entry
        }

        override fun remove() {
            if (lastIndex < 0) {
                throw IllegalStateException()
            }
            removeAt(lastIndex)
            if (lastIndex < nextIndex) {
                nextIndex--
            }
            lastIndex = -1
        }
    }

    private inner class Entry(
        private val index: Int
    ) : MutableMap.MutableEntry<String, String> {
        override val key: String
            get() = checkNotNull(slots[keyIndex(index)])

        override val value: String
            get() = checkNotNull(slots[valueIndex(index)])

        override fun setValue(newValue: String): String {
            val previous = value
            ensureMutable(entryCount)
            slots[valueIndex(index)] = newValue
            return previous
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Map.Entry<*, *>) {
                return false
            }
            return key == other.key && value == other.value
        }

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()
    }

    companion object {
        private const val ENTRY_WIDTH = 2
        private val EMPTY = arrayOfNulls<String>(0)

        private fun capacity(expectedSize: Int): Int =
            when {
                expectedSize <= 3 -> DEFAULT_HEADER_CAPACITY
                else -> expectedSize
            }

        private fun keyIndex(entryIndex: Int): Int = entryIndex * ENTRY_WIDTH

        private fun valueIndex(entryIndex: Int): Int = keyIndex(entryIndex) + 1

        private fun slotSize(entryCapacity: Int): Int = entryCapacity * ENTRY_WIDTH
    }
}

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
    private val delegate: MutableMap<String, String> = newHeaderMap(),
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
    override fun copy(): Header {
        if (isEmpty()) {
            return empty()
        }
        if (delegate is SmallHeaderMap) {
            return DefaultHeader(delegate.copyMap())
        }
        return DefaultHeader(newHeaderMap(size).also { it.putAll(this) })
    }

    /**
     * Executes a write operation if the header is not read-only.
     *
     * @param block The block of code to execute for the write operation
     * @return The result of the block execution
     * @throws UnsupportedOperationException if the header is read-only
     */
    private inline fun <T> write(block: () -> T): T {
        if (isReadOnly) {
            throw UnsupportedOperationException("Header is read only.")
        }
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
        if (other !is DefaultHeader) return false
        return delegate == other.delegate
    }

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = "DefaultHeader(delegate=$delegate)"
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
    return DefaultHeader(newHeaderMap(size).also { it.putAll(this) })
}
