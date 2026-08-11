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

package me.ahoo.wow.api.query

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.Period
import java.time.Year
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Interface representing a dynamic document that can store arbitrary key-value pairs.
 *
 * This interface extends [MutableMap] and provides additional convenience methods for
 * type-safe value retrieval and nested document access. It's designed for working with
 * dynamic data structures where the schema is not known at compile time.
 */
interface DynamicDocument : MutableMap<String, Any?> {
    /**
     * Retrieves a value from the document with type casting.
     *
     * This method provides type-safe access to document values by performing an unchecked cast.
     * Use with caution and ensure the value is actually of the expected type.
     *
     * @param V The expected type of the value.
     * @param key The key to retrieve the value for.
     * @return The value cast to type V.
     * @throws ClassCastException if the value cannot be cast to the specified type.
     * @throws NoSuchElementException if the key is not present in the document.
     */
    @Suppress("UNCHECKED_CAST")
    fun <V> getValue(key: String): V =
        get(key) as? V
            ?: throw NoSuchElementException("Key not found: $key")

    /**
     * Retrieves a nested document from the current document.
     *
     * This method is used to access nested dynamic documents within the current document.
     * The returned document can itself contain nested documents, allowing for deep traversal.
     *
     * @param key The key of the nested document.
     * @return The nested dynamic document.
     * @throws NoSuchElementException if the key is not present.
     * @throws ClassCastException if the value is not a DynamicDocument.
     */
    fun getNestedDocument(key: String): DynamicDocument
}

/**
 * A simple implementation of [DynamicDocument] that delegates to a [MutableMap].
 *
 * This class provides a concrete implementation of the DynamicDocument interface by
 * wrapping an existing mutable map. It supports all standard map operations and
 * provides the additional methods defined in the DynamicDocument interface.
 *
 * @property delegation The underlying mutable map that stores the document data.
 */
class SimpleDynamicDocument(
    val delegation: MutableMap<String, Any?>
) : DynamicDocument,
    MutableMap<String, Any?> by delegation {
    /**
     * Retrieves a nested document, converting it to a DynamicDocument if necessary.
     *
     * If the nested value is already a DynamicDocument, it is returned directly.
     * Otherwise, if it's a MutableMap, it is converted to a SimpleDynamicDocument.
     *
     * @param key The key of the nested document.
     * @return The nested dynamic document.
     * @throws NoSuchElementException if the key is not present.
     * @throws ClassCastException if the value cannot be converted to a DynamicDocument.
     */
    override fun getNestedDocument(key: String): DynamicDocument {
        val value = get(key) ?: throw NoSuchElementException("Key not found: $key")
        return when (value) {
            is DynamicDocument -> {
                value
            }

            is MutableMap<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (value as MutableMap<String, Any?>).toDynamicDocument()
            }

            else -> {
                throw ClassCastException("Value [$value] is not a DynamicDocument or MutableMap")
            }
        }
    }

    companion object {
        /**
         * Extension function to convert a MutableMap to a SimpleDynamicDocument.
         *
         * This provides a convenient way to wrap existing maps as dynamic documents.
         *
         * @receiver The mutable map to convert.
         * @return A new SimpleDynamicDocument wrapping the map.
         */
        @Suppress("UNCHECKED_CAST")
        fun MutableMap<String, *>.toDynamicDocument(): SimpleDynamicDocument = SimpleDynamicDocument(
            this as MutableMap<String, Any?>
        )
    }
}

/**
 * Framework-owned immutable dynamic document used by the canonical query API.
 *
 * The legacy [DynamicDocument] type remains a [MutableMap] for binary compatibility. This implementation takes a
 * deep snapshot at its boundary and rejects every mutation operation. Binary values are copied again when read so
 * callers cannot mutate the internal snapshot through a returned array.
 *
 * Accepted values are null; strings, booleans, characters and the primitive number wrappers; [BigInteger],
 * [BigDecimal], [UUID] and immutable `java.time` values; nested string-keyed maps, lists, sets, object arrays and
 * primitive arrays. Set elements are restricted to the accepted scalar values because container and array equality
 * cannot preserve set cardinality safely. Enum constants and every other value are rejected instead of being
 * retained by reference. Cyclic value graphs are rejected at the construction boundary.
 */
class ImmutableDynamicDocument private constructor(
    private val snapshot: Map<String, Any?>
) : AbstractMutableMap<String, Any?>(),
    DynamicDocument {
    private val structuralEntries = StructuralEntrySet(snapshot)

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = structuralEntries

    override fun get(key: String): Any? = snapshot[key].toDetachedValue()

    override fun put(key: String, value: Any?): Any? = immutableMutation()

    override fun remove(key: String): Any? = immutableMutation()

    override fun clear(): Unit = immutableMutation()

    override fun putAll(from: Map<out String, Any?>): Unit = immutableMutation()

    override fun getNestedDocument(key: String): DynamicDocument {
        val value = snapshot[key] ?: throw NoSuchElementException("Key not found: $key")
        return value as? ImmutableDynamicDocument
            ?: throw ClassCastException("Value at key [$key] is not a DynamicDocument")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        return when (other) {
            is ImmutableDynamicDocument -> entries == other.entries
            is Map<*, *> -> super.equals(other)
            else -> false
        }
    }

    override fun hashCode(): Int = entries.hashCode()

    companion object {
        fun copyOf(source: Map<String, *>): ImmutableDynamicDocument =
            fromUntypedMap(source, IdentityHashMap())

        private fun fromUntypedMap(
            source: Map<*, *>,
            recursionStack: IdentityHashMap<Any, Unit>
        ): ImmutableDynamicDocument = snapshotContainer(source, recursionStack) {
            val result = LinkedHashMap<String, Any?>(source.size)
            source.forEach { (key, value) ->
                require(key is String) { "Dynamic document keys must be strings." }
                result[key] = snapshotValue(value, recursionStack)
            }
            ImmutableDynamicDocument(Collections.unmodifiableMap(result))
        }

        private fun snapshotValue(value: Any?, recursionStack: IdentityHashMap<Any, Unit>): Any? = when (value) {
            is ImmutableDynamicDocument -> value
            is Map<*, *> -> fromUntypedMap(value, recursionStack)
            is List<*> -> snapshotContainer(value, recursionStack) {
                ListSnapshot(value.map { snapshotValue(it, recursionStack) })
            }
            is Set<*> -> snapshotSet(value, recursionStack)
            is Array<*> -> snapshotContainer(value, recursionStack) {
                ObjectArraySnapshot(
                    value.javaClass.componentType,
                    value.map { snapshotValue(it, recursionStack) }
                )
            }
            is ByteArray -> BinarySnapshot(value)
            else -> primitiveArraySnapshot(value) ?: acceptedScalar(value)
        }

        private fun snapshotSet(
            source: Set<*>,
            recursionStack: IdentityHashMap<Any, Unit>
        ): SetSnapshot = snapshotContainer(source, recursionStack) {
            val values = source.mapTo(LinkedHashSet()) { value ->
                require(value == null || !recursionStack.containsKey(value)) {
                    "Cyclic dynamic document value graph."
                }
                acceptedScalar(value)
            }
            require(values.size == source.size) {
                "Dynamic document set values must preserve cardinality under value equality."
            }
            SetSnapshot(values)
        }

        private inline fun <T> snapshotContainer(
            source: Any,
            recursionStack: IdentityHashMap<Any, Unit>,
            snapshot: () -> T
        ): T {
            require(!recursionStack.containsKey(source)) {
                "Cyclic dynamic document value graph."
            }
            recursionStack[source] = Unit
            return try {
                snapshot()
            } finally {
                recursionStack.remove(source)
            }
        }

        private fun primitiveArraySnapshot(value: Any?): PrimitiveArraySnapshot? = PrimitiveArraySnapshot.copyOf(value)

        private fun acceptedScalar(value: Any?): Any? = when (value) {
            null,
            is String,
            is Boolean,
            is Char,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            is BigInteger,
            is BigDecimal,
            is UUID,
            is Instant,
            is Duration,
            is Period,
            is LocalDate,
            is LocalTime,
            is LocalDateTime,
            is OffsetTime,
            is OffsetDateTime,
            is ZonedDateTime,
            is Year,
            is YearMonth,
            is MonthDay -> value

            else -> throw IllegalArgumentException("Unsupported dynamic document value type.")
        }
    }
}

private class StructuralEntrySet(
    private val snapshot: Map<String, Any?>
) : AbstractMutableSet<MutableMap.MutableEntry<String, Any?>>() {
    override val size: Int
        get() = snapshot.size

    override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, Any?>> {
        val iterator = snapshot.entries.iterator()
        return object : MutableIterator<MutableMap.MutableEntry<String, Any?>> {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): MutableMap.MutableEntry<String, Any?> {
                val entry = iterator.next()
                return StructuralEntry(entry.key, entry.value)
            }

            override fun remove(): Unit = immutableMutation()
        }
    }

    override fun contains(element: MutableMap.MutableEntry<String, Any?>): Boolean =
        containsEntry(element)

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Set<*> || size != other.size) {
            return false
        }
        return other.all(::containsEntry)
    }

    override fun hashCode(): Int = snapshot.entries.sumOf { (key, value) ->
        key.hashCode() xor (value?.hashCode() ?: 0)
    }

    override fun add(element: MutableMap.MutableEntry<String, Any?>): Boolean = immutableMutation()

    override fun addAll(elements: Collection<MutableMap.MutableEntry<String, Any?>>): Boolean = immutableMutation()

    override fun remove(element: MutableMap.MutableEntry<String, Any?>): Boolean = immutableMutation()

    override fun removeAll(elements: Collection<MutableMap.MutableEntry<String, Any?>>): Boolean = immutableMutation()

    override fun retainAll(elements: Collection<MutableMap.MutableEntry<String, Any?>>): Boolean = immutableMutation()

    override fun clear(): Unit = immutableMutation()

    private fun containsEntry(candidate: Any?): Boolean {
        if (candidate !is Map.Entry<*, *> || candidate.key !is String) {
            return false
        }
        val key = candidate.key as String
        if (!snapshot.containsKey(key)) {
            return false
        }
        return StructuralEntry(key, snapshot[key]) == candidate
    }
}

private class StructuralEntry(
    override val key: String,
    private val snapshotValue: Any?
) : MutableMap.MutableEntry<String, Any?> {
    override val value: Any?
        get() = snapshotValue.toDetachedValue()

    override fun setValue(newValue: Any?): Any? = immutableMutation()

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is Map.Entry<*, *> || key != other.key) {
            return false
        }
        return if (other is StructuralEntry) {
            snapshotValue == other.snapshotValue
        } else {
            value == other.value
        }
    }

    override fun hashCode(): Int = key.hashCode() xor (snapshotValue?.hashCode() ?: 0)

    override fun toString(): String = "$key=$value"
}

private class BinarySnapshot(value: ByteArray) {
    private val value: ByteArray = value.copyOf()

    fun detached(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is BinarySnapshot && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
}

private class ListSnapshot(values: Collection<Any?>) {
    val values: List<Any?> = Collections.unmodifiableList(ArrayList(values))

    override fun equals(other: Any?): Boolean = other is ListSnapshot && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

private class SetSnapshot(values: Collection<Any?>) {
    val values: Set<Any?> = Collections.unmodifiableSet(LinkedHashSet(values))

    override fun equals(other: Any?): Boolean = other is SetSnapshot && values == other.values

    override fun hashCode(): Int = values.hashCode()
}

private class ObjectArraySnapshot(
    private val componentType: Class<*>,
    private val values: List<Any?>
) {
    init {
        try {
            detached()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Object array component type cannot represent immutable snapshot values.")
        }
    }

    fun detached(): Any {
        val result = java.lang.reflect.Array.newInstance(componentType, values.size)
        values.forEachIndexed { index, value ->
            java.lang.reflect.Array.set(result, index, value.toDetachedValue())
        }
        return result
    }

    override fun equals(other: Any?): Boolean = other is ObjectArraySnapshot &&
        componentType == other.componentType && values == other.values

    override fun hashCode(): Int = 31 * componentType.hashCode() + values.hashCode()
}

private class PrimitiveArraySnapshot private constructor(
    private val kind: Kind,
    private val value: Any
) {
    fun detached(): Any = when (kind) {
        Kind.SHORT -> (value as ShortArray).copyOf()
        Kind.INT -> (value as IntArray).copyOf()
        Kind.LONG -> (value as LongArray).copyOf()
        Kind.FLOAT -> (value as FloatArray).copyOf()
        Kind.DOUBLE -> (value as DoubleArray).copyOf()
        Kind.BOOLEAN -> (value as BooleanArray).copyOf()
        Kind.CHAR -> (value as CharArray).copyOf()
    }

    override fun equals(other: Any?): Boolean = other is PrimitiveArraySnapshot &&
        kind == other.kind && contentEquals(value, other.value)

    override fun hashCode(): Int = 31 * kind.hashCode() + contentHashCode(value)

    private enum class Kind {
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        BOOLEAN,
        CHAR
    }

    companion object {
        fun copyOf(value: Any?): PrimitiveArraySnapshot? = when (value) {
            is ShortArray -> PrimitiveArraySnapshot(Kind.SHORT, value.copyOf())
            is IntArray -> PrimitiveArraySnapshot(Kind.INT, value.copyOf())
            is LongArray -> PrimitiveArraySnapshot(Kind.LONG, value.copyOf())
            is FloatArray -> PrimitiveArraySnapshot(Kind.FLOAT, value.copyOf())
            is DoubleArray -> PrimitiveArraySnapshot(Kind.DOUBLE, value.copyOf())
            is BooleanArray -> PrimitiveArraySnapshot(Kind.BOOLEAN, value.copyOf())
            is CharArray -> PrimitiveArraySnapshot(Kind.CHAR, value.copyOf())
            else -> null
        }

        private fun contentEquals(left: Any, right: Any): Boolean = when (left) {
            is ShortArray -> left.contentEquals(right as ShortArray)
            is IntArray -> left.contentEquals(right as IntArray)
            is LongArray -> left.contentEquals(right as LongArray)
            is FloatArray -> left.contentEquals(right as FloatArray)
            is DoubleArray -> left.contentEquals(right as DoubleArray)
            is BooleanArray -> left.contentEquals(right as BooleanArray)
            is CharArray -> left.contentEquals(right as CharArray)
            else -> false
        }

        private fun contentHashCode(value: Any): Int = when (value) {
            is ShortArray -> value.contentHashCode()
            is IntArray -> value.contentHashCode()
            is LongArray -> value.contentHashCode()
            is FloatArray -> value.contentHashCode()
            is DoubleArray -> value.contentHashCode()
            is BooleanArray -> value.contentHashCode()
            is CharArray -> value.contentHashCode()
            else -> 0
        }
    }
}

private fun Any?.toDetachedValue(): Any? = when (this) {
    is BinarySnapshot -> detached()
    is ListSnapshot -> Collections.unmodifiableList(values.map { it.toDetachedValue() })
    is SetSnapshot -> Collections.unmodifiableSet(values.mapTo(LinkedHashSet()) { it.toDetachedValue() })
    is ObjectArraySnapshot -> detached()
    is PrimitiveArraySnapshot -> detached()
    else -> this
}

private fun immutableMutation(): Nothing = throw UnsupportedOperationException("Dynamic document is immutable.")
