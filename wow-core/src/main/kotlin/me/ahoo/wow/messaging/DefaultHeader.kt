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
 * It keeps its entries in an internal mutable map while enforcing read-only constraints.
 *
 * [copy] is copy-on-write: a copy shares the entries of its source until either of them changes them (through a write
 * method or a view), and only then takes a private map. Every message copy copies its headers, and most copies are
 * never changed, so they no longer re-hash every entry. The entries of a header built around a map the caller passed
 * in ([DefaultHeader] with a `delegate`) are never shared, because the caller may still change that map: its first
 * copy copies them at once, as before.
 *
 * @param delegate The underlying mutable map that stores header key-value pairs
 * @property isReadOnly Whether this header is read-only (volatile for thread safety)
 * @author ahoo wang
 */
class DefaultHeader private constructor(
    private var delegate: MutableMap<String, String>,
    isReadOnly: Boolean,
    /** Whether this header created [delegate] itself, so that its copies may share it. */
    private val owned: Boolean,
) : Header {
    /**
     * Creates a header that stores its entries in [delegate]; changes the caller makes to [delegate] afterwards show
     * in this header (not in its copies).
     */
    constructor(
        delegate: MutableMap<String, String>,
        isReadOnly: Boolean = false
    ) : this(delegate, isReadOnly, owned = false)

    /** Creates an empty, mutable header. */
    constructor() : this(LinkedHashMap(), isReadOnly = false, owned = true)

    @Volatile
    override var isReadOnly: Boolean = isReadOnly

    /**
     * Whether [delegate] may be shared with another header (a copy, or the source of this copy). A header whose flag
     * is `false` is the only one holding its [delegate]; a write first takes a private copy when it is `true`.
     */
    @Volatile
    private var shared: Boolean = false

    companion object {
        /**
         * Creates an empty header instance.
         *
         * @return A new empty [Header] instance
         */
        fun empty(): Header = DefaultHeader()

        /** A header that owns [entries], a map nobody else holds, so that its copies may share it. */
        internal fun owning(entries: MutableMap<String, String>): DefaultHeader =
            DefaultHeader(entries, isReadOnly = false, owned = true)
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
     * The copy is mutable and not read-only, regardless of the original's state. Changes to either one never show in
     * the other: the two share their entries until one of them changes them (copy-on-write).
     *
     * @return A new mutable copy of this header
     */
    override fun copy(): Header {
        val entries = delegate
        if (!owned || entries.isEmpty()) {
            return owning(LinkedHashMap(entries))
        }
        shared = true
        return owning(entries).also { it.shared = true }
    }

    /** The entries to change: a private copy when they may be shared. */
    private fun ownEntries(): MutableMap<String, String> {
        if (shared) {
            delegate = LinkedHashMap(delegate)
            shared = false
        }
        return delegate
    }

    /**
     * Executes a write operation if the header is not read-only.
     *
     * @param T The result type.
     * @param block The block of code to execute for the write operation, on entries no other header holds
     * @return The result of the block execution
     * @throws UnsupportedOperationException if the header is read-only
     */
    private inline fun <T> write(block: (MutableMap<String, String>) -> T): T {
        if (isReadOnly) {
            throw UnsupportedOperationException("Header is read only.")
        }
        return block(ownEntries())
    }

    override val size: Int
        get() = delegate.size

    override fun isEmpty(): Boolean = delegate.isEmpty()

    override fun containsKey(key: String): Boolean = delegate.containsKey(key)

    override fun containsValue(value: String): Boolean = delegate.containsValue(value)

    override fun get(key: String): String? = delegate[key]

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
            it.put(key, value)
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
            it.remove(key)
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
            it.remove(key, value)
        }

    /**
     * Copies all of the mappings from the specified map to this header.
     *
     * @param from Mappings to be stored in this header
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun putAll(from: Map<out String, String>) {
        write {
            it.putAll(from)
        }
    }

    /**
     * Removes all mappings from this header.
     *
     * @throws UnsupportedOperationException if the header is read-only
     */
    override fun clear() {
        write {
            it.clear()
        }
    }

    /**
     * The keys of this header, a live view. As before 9.3.0, changes through a view do not check [isReadOnly]; they
     * never reach a copy of this header or the header it was copied from.
     */
    override val keys: MutableSet<String>
        get() = KeysView()

    /** The values of this header, a live view; see [keys]. */
    override val values: MutableCollection<String>
        get() = ValuesView()

    /** The entries of this header, a live view; see [keys]. */
    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() = EntriesView()

    /** Whether this header and [other] currently share their entries (copy-on-write); for tests. */
    internal fun sharesEntriesWith(other: DefaultHeader): Boolean = delegate === other.delegate

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DefaultHeader) return false
        return delegate == other.delegate
    }

    override fun hashCode(): Int = delegate.hashCode()

    override fun toString(): String = "DefaultHeader(delegate=$delegate)"

    /**
     * Iterates the entries as they are when it starts. A removal or a [MutableMap.MutableEntry.setValue] through it
     * changes the iterated map in place only while this header still holds it alone; otherwise (it was copied since,
     * or this header took a private map) the change goes to this header's own entries, by key.
     */
    private inner class EntryIterator<T>(
        private val extract: (MutableMap.MutableEntry<String, String>, MutableMap<String, String>) -> T,
    ) : MutableIterator<T> {
        private val iterated = delegate
        private val iterator = iterated.entries.iterator()
        private var last: MutableMap.MutableEntry<String, String>? = null

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): T {
            val entry = iterator.next()
            last = entry
            return extract(entry, iterated)
        }

        override fun remove() {
            val entry = checkNotNull(last) { "next() has not been called, or remove() was already called." }
            last = null
            if (changesInPlace(iterated)) {
                iterator.remove()
            } else {
                ownEntries().remove(entry.key)
            }
        }
    }

    /** Whether a change through a view of [iterated] may change it in place: this header holds it, and holds it alone. */
    private fun changesInPlace(iterated: MutableMap<String, String>): Boolean = delegate === iterated && !shared

    /** An entry of [iterated] seen through [EntriesView]; see [EntryIterator]. */
    private inner class ViewEntry(
        private val entry: MutableMap.MutableEntry<String, String>,
        private val iterated: MutableMap<String, String>,
    ) : MutableMap.MutableEntry<String, String> {
        override val key: String
            get() = entry.key
        override val value: String
            get() = if (changesInPlace(iterated)) entry.value else delegate[entry.key] ?: entry.value

        override fun setValue(newValue: String): String {
            if (changesInPlace(iterated)) {
                return entry.setValue(newValue)
            }
            val previous = value
            ownEntries()[entry.key] = newValue
            return previous
        }

        override fun equals(other: Any?): Boolean {
            if (other !is Map.Entry<*, *>) return false
            return key == other.key && value == other.value
        }

        override fun hashCode(): Int = key.hashCode() xor value.hashCode()

        override fun toString(): String = "$key=$value"
    }

    private inner class KeysView : AbstractMutableSet<String>() {
        override val size: Int
            get() = delegate.size

        override fun contains(element: String): Boolean = delegate.containsKey(element)

        override fun add(element: String): Boolean = throw UnsupportedOperationException()

        override fun remove(element: String): Boolean {
            if (!delegate.containsKey(element)) {
                return false
            }
            ownEntries().remove(element)
            return true
        }

        override fun clear() {
            ownEntries().clear()
        }

        override fun iterator(): MutableIterator<String> = EntryIterator { entry, _ -> entry.key }
    }

    private inner class ValuesView : AbstractMutableCollection<String>() {
        override val size: Int
            get() = delegate.size

        override fun contains(element: String): Boolean = delegate.containsValue(element)

        override fun add(element: String): Boolean = throw UnsupportedOperationException()

        override fun clear() {
            ownEntries().clear()
        }

        override fun iterator(): MutableIterator<String> = EntryIterator { entry, _ -> entry.value }
    }

    private inner class EntriesView : AbstractMutableSet<MutableMap.MutableEntry<String, String>>() {
        override val size: Int
            get() = delegate.size

        override fun contains(element: MutableMap.MutableEntry<String, String>): Boolean =
            delegate.entries.contains(element)

        override fun add(element: MutableMap.MutableEntry<String, String>): Boolean =
            throw UnsupportedOperationException()

        override fun remove(element: MutableMap.MutableEntry<String, String>): Boolean {
            if (!delegate.entries.contains(element)) {
                return false
            }
            ownEntries().remove(element.key)
            return true
        }

        override fun clear() {
            ownEntries().clear()
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> =
            EntryIterator { entry, iterated -> ViewEntry(entry, iterated) }
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
    return DefaultHeader.owning(LinkedHashMap(this))
}
