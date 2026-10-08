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
 * Unlike a plain map, iterating a header whose entries are shared is not fail-fast: a write during the iteration
 * takes private entries and the iteration goes on over the shared ones, without a `ConcurrentModificationException`.
 *
 * @param delegate The entries
 * @param isReadOnly Whether this header starts read-only
 * @param share `null` while this header holds [delegate] alone; the token of the headers it is shared with; or
 * [CALLER_MAP] when a caller passed [delegate] in (never shared: the caller may still change it)
 * @author ahoo wang
 */
class DefaultHeader private constructor(
    delegate: MutableMap<String, String>,
    isReadOnly: Boolean,
    share: Share?,
) : Header,
    MutableMap<String, String> {
    /**
     * Creates a header that stores its entries in [delegate]; changes the caller makes to [delegate] afterwards show
     * in this header (not in its copies).
     */
    constructor(
        delegate: MutableMap<String, String> = LinkedHashMap(),
        isReadOnly: Boolean = false
    ) : this(delegate, isReadOnly, CALLER_MAP)

    /** Creates an empty, mutable header. */
    constructor() : this(LinkedHashMap(), isReadOnly = false, share = null)

    /**
     * Held by every header that shares one entry map. A header drops it (never clears anything on it) when a write
     * takes private entries, so a write racing a copy can leak at most that one write into the copy, never make two
     * headers go on writing to one map. The entry map stays a plain [LinkedHashMap]: a map subclass would make the
     * JVM's shared `HashMap` call sites megamorphic.
     */
    private class Share

    // Not volatile: a reader that sees the previous map sees entries no header changes any more.
    private var delegate: MutableMap<String, String> = delegate

    @Volatile
    private var share: Share? = null

    init {
        // Only a copy or a caller's map stores it: a volatile store of `null` would cost every new header a fence.
        if (share != null) {
            this.share = share
        }
    }

    /** Whether this header is read-only (volatile for thread safety). */
    @Volatile
    override var isReadOnly: Boolean = isReadOnly

    companion object {
        /**
         * Creates an empty header instance.
         *
         * @return A new empty [Header] instance
         */
        fun empty(): Header = DefaultHeader()

        /** Marks a map a caller passed in: never shared, copied at once by [copy]. */
        private val CALLER_MAP = Share()

        /** A header that owns a copy of [entries], so that its copies may share it. */
        internal fun owning(entries: Map<String, String>): DefaultHeader =
            DefaultHeader(LinkedHashMap(entries), isReadOnly = false, share = null)
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
        val current = delegate
        val token = share
        if (token === CALLER_MAP || current.isEmpty()) {
            return owning(current)
        }
        val shared = token ?: Share().also { share = it }
        return DefaultHeader(current, isReadOnly = false, share = shared)
    }

    /** The entries to change: a new private map when the current one may be shared. */
    private fun ownEntries(): MutableMap<String, String> {
        val token = share
        if (token == null || token === CALLER_MAP) {
            return delegate
        }
        share = null
        return LinkedHashMap(delegate).also { delegate = it }
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
     * The keys of this header, a live view. As before 9.3.0, changes through a view of a writable header do not
     * check [isReadOnly]; they never reach a copy of this header or the header it was copied from.
     *
     * Iterating a view iterates the backing map itself. A writable header whose entries are shared first takes
     * private entries (as a write would), so whatever the iteration changes stays in this header. A read-only header
     * iterates the shared entries as they are: its views must not be used to change it. An iterator or entry kept
     * past a [copy] must not be used to change the header either.
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

    /** The map a view iterates; see [keys]. */
    private fun iterated(): MutableMap<String, String> = if (isReadOnly) delegate else ownEntries()

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

        override fun iterator(): MutableIterator<String> = iterated().keys.iterator()
    }

    private inner class ValuesView : AbstractMutableCollection<String>() {
        override val size: Int
            get() = delegate.size

        override fun contains(element: String): Boolean = delegate.containsValue(element)

        override fun add(element: String): Boolean = throw UnsupportedOperationException()

        override fun clear() {
            ownEntries().clear()
        }

        override fun iterator(): MutableIterator<String> = iterated().values.iterator()
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
            iterated().entries.iterator()
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
    return DefaultHeader.owning(this)
}
