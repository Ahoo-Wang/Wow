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

package me.ahoo.wow.query.expression

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.QueryValue
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.IdentityHashMap

object QueryValueNormalizer {
    fun normalize(value: Any?): QueryValue = normalize(value, IdentityHashMap())

    fun normalizeElements(value: Any?): List<QueryValue> =
        when (val normalized = normalize(value)) {
            is QueryValue.ListValue -> normalized.values
            else -> invalidQuery()
        }

    @Suppress("CyclomaticComplexMethod")
    private fun normalize(value: Any?, recursionStack: IdentityHashMap<Any, Unit>): QueryValue =
        try {
            when (value) {
                null -> QueryValue.NullValue
                is QueryValue -> value
                is Boolean -> QueryValue.BooleanValue(value)
                is Byte -> QueryValue.IntegerValue(value.toLong())
                is Short -> QueryValue.IntegerValue(value.toLong())
                is Int -> QueryValue.IntegerValue(value.toLong())
                is Long -> QueryValue.IntegerValue(value)
                is BigInteger -> QueryValue.IntegerValue(value.longValueExact())
                is Float -> QueryValue.FloatingValue(value.toDouble())
                is Double -> QueryValue.FloatingValue(value)
                is BigDecimal -> QueryValue.DecimalValue(value)
                is String -> QueryValue.StringValue(value)
                is Char -> QueryValue.StringValue(value.toString())
                is Instant -> QueryValue.InstantValue(value)
                is Enum<*> -> QueryValue.EnumValue(value.name)
                is ByteArray -> QueryValue.BinaryValue(value)
                is Map<*, *> -> normalizeMap(value, recursionStack)
                is Iterable<*> -> normalizeIterable(value, recursionStack)
                is Array<*> -> normalizeArray(value, recursionStack)
                is ShortArray -> normalizeArray(value.size) { value[it] }
                is IntArray -> normalizeArray(value.size) { value[it] }
                is LongArray -> normalizeArray(value.size) { value[it] }
                is FloatArray -> normalizeArray(value.size) { value[it] }
                is DoubleArray -> normalizeArray(value.size) { value[it] }
                is BooleanArray -> normalizeArray(value.size) { value[it] }
                is CharArray -> normalizeArray(value.size) { value[it] }
                else -> invalidQuery()
            }
        } catch (error: QueryException) {
            throw error
        } catch (_: RuntimeException) {
            invalidQuery()
        }

    private fun normalizeMap(source: Map<*, *>, recursionStack: IdentityHashMap<Any, Unit>): QueryValue.ObjectValue =
        withContainer(source, recursionStack) {
            val snapshot = LinkedHashMap<String, QueryValue>(source.size)
            val iterator = source.entries.iterator()
            var visited = 0
            while (iterator.hasNext()) {
                val entry = iterator.next()
                visited++
                val key = entry.key as? String ?: invalidQuery()
                if (key.isBlank()) {
                    invalidQuery()
                }
                snapshot[key] = normalize(entry.value, recursionStack)
            }
            if (visited != source.size || snapshot.size != visited) {
                invalidQuery()
            }
            QueryValue.ObjectValue(snapshot)
        }

    private fun normalizeIterable(
        source: Iterable<*>,
        recursionStack: IdentityHashMap<Any, Unit>
    ): QueryValue.ListValue = withContainer(source, recursionStack) {
        val snapshot = ArrayList<QueryValue>()
        val iterator = source.iterator()
        while (iterator.hasNext()) {
            snapshot += normalize(iterator.next(), recursionStack)
        }
        QueryValue.ListValue(snapshot)
    }

    private fun normalizeArray(source: Array<*>, recursionStack: IdentityHashMap<Any, Unit>): QueryValue.ListValue =
        withContainer(source, recursionStack) {
            QueryValue.ListValue(source.indices.map { normalize(source[it], recursionStack) })
        }

    private inline fun normalizeArray(size: Int, valueAt: (Int) -> Any): QueryValue.ListValue =
        QueryValue.ListValue(List(size) { normalize(valueAt(it)) })

    private inline fun <T> withContainer(
        source: Any,
        recursionStack: IdentityHashMap<Any, Unit>,
        materialize: () -> T
    ): T {
        if (recursionStack.put(source, Unit) != null) {
            invalidQuery()
        }
        return try {
            materialize()
        } finally {
            recursionStack.remove(source)
        }
    }
}

internal fun invalidQuery(): Nothing = throw QueryException(
    QueryErrorCode.INVALID_QUERY,
    QueryStage.NORMALIZE,
    QueryErrorReason.INVALID_REQUEST
)
