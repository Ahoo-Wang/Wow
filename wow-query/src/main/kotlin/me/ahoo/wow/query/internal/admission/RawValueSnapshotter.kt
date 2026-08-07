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

package me.ahoo.wow.query.internal.admission

import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Date
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Converts legacy `Any` values into the closed, deeply immutable normalized value algebra.
 *
 * Each public operation owns its identity session, so this stateless component is safe to reuse concurrently.
 */
internal class RawValueSnapshotter(
    private val limits: QueryAdmissionLimits,
) {
    fun snapshot(
        rawValue: Any?,
        path: QueryRejectionPath,
        budget: AdmissionBudget,
    ): NormalizedValue = snapshotValue(rawValue, path, depth = 1, ValueSession(), budget)

    fun snapshotRequiredIterable(
        rawValue: Any?,
        path: QueryRejectionPath,
        budget: AdmissionBudget,
    ): NormalizedValue.ListValue {
        if (rawValue !is Iterable<*>) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
        budget.enterValue(path)
        return snapshotIterable(rawValue, path, depth = 1, ValueSession(), budget)
    }

    fun snapshotRequiredStringIterable(
        rawValue: Any?,
        path: QueryRejectionPath,
        budget: AdmissionBudget,
    ): NormalizedValue.ListValue {
        if (rawValue !is Iterable<*>) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
        budget.enterValue(path)
        val session = ValueSession()
        enterValue(rawValue, path, session)
        try {
            val result = ArrayList<NormalizedValue>()
            val iterator = rawValue.iterator()
            while (iterator.hasNext()) {
                if (result.size == limits.maxCollectionSize) {
                    rejectBudget(path, QueryRejectionCode.COLLECTION_LIMIT_EXCEEDED)
                }
                val itemPath = path.index(result.size)
                val item = iterator.next()
                budget.enterValue(itemPath)
                if (item !is String) {
                    rejectInvalid(itemPath, QueryRejectionCode.INVALID_VALUE_TYPE)
                }
                budget.consumeString(item, itemPath)
                result += NormalizedValue.Text(item)
            }
            return NormalizedValue.ListValue(result)
        } finally {
            session.active.remove(rawValue)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun snapshotValue(
        rawValue: Any?,
        path: QueryRejectionPath,
        depth: Int,
        session: ValueSession,
        budget: AdmissionBudget,
    ): NormalizedValue {
        if (depth > limits.maxValueDepth) {
            rejectBudget(path, QueryRejectionCode.VALUE_DEPTH_LIMIT_EXCEEDED)
        }
        budget.enterValue(path)
        return when (rawValue) {
            null -> NormalizedValue.Null
            is NormalizedValue -> rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
            is Boolean -> NormalizedValue.BooleanValue(rawValue)
            is String -> {
                budget.consumeString(rawValue, path)
                NormalizedValue.Text(rawValue)
            }
            is Char -> normalizedText(rawValue.toString(), path, budget)
            is UUID -> normalizedText(rawValue.toString(), path, budget)
            is Enum<*> -> normalizedText(rawValue.name, path, budget)
            is Number -> normalizeNumber(rawValue, path, budget)
            is Instant -> NormalizedValue.InstantValue(rawValue)
            is Date -> {
                val instant = try {
                    rawValue.toInstant()
                } catch (error: UnsupportedOperationException) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE, error)
                }
                NormalizedValue.InstantValue(instant)
            }
            is OffsetDateTime -> NormalizedValue.InstantValue(rawValue.toInstant())
            is ZonedDateTime -> NormalizedValue.InstantValue(rawValue.toInstant())
            is ByteArray -> {
                budget.consumeBytes(rawValue.size, path)
                NormalizedValue.Bytes(rawValue)
            }
            is Map<*, *> -> snapshotMap(rawValue, path, depth, session, budget)
            is Iterable<*> -> snapshotIterable(rawValue, path, depth, session, budget)
            is Array<*> -> snapshotArray(rawValue, path, depth, session, budget)
            else -> rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
    }

    private fun normalizedText(
        value: String,
        path: QueryRejectionPath,
        budget: AdmissionBudget,
    ): NormalizedValue.Text {
        budget.consumeString(value, path)
        return NormalizedValue.Text(value)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun normalizeNumber(
        number: Number,
        path: QueryRejectionPath,
        budget: AdmissionBudget,
    ): NormalizedValue {
        if (!number.isSupported()) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
        }
        budget.consumeNumber(number, path)
        val decimal = try {
            when (number) {
                is BigDecimal -> number
                is BigInteger -> number.toBigDecimal()
                is Byte,
                is Short,
                is Int,
                is Long,
                -> BigDecimal.valueOf(number.toLong())
                is Float -> {
                    if (!number.isFinite()) {
                        rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
                    }
                    BigDecimal.valueOf(number.toDouble())
                }
                is Double -> {
                    if (!number.isFinite()) {
                        rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
                    }
                    BigDecimal.valueOf(number)
                }
                else -> error("Supported numeric types are exhaustive.")
            }
        } catch (error: NumberFormatException) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE, error)
        }
        val exactLong = try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
        if (exactLong != null) {
            return NormalizedValue.Int64(exactLong)
        }
        return try {
            NormalizedValue.Decimal(decimal)
        } catch (error: ArithmeticException) {
            rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE, error)
        }
    }

    private fun Number.isSupported(): Boolean =
        when (this) {
            is BigDecimal,
            is BigInteger,
            is Byte,
            is Short,
            is Int,
            is Long,
            is Float,
            is Double,
            -> true
            else -> false
        }

    private fun snapshotIterable(
        values: Iterable<*>,
        path: QueryRejectionPath,
        depth: Int,
        session: ValueSession,
        budget: AdmissionBudget,
    ): NormalizedValue.ListValue {
        enterValue(values, path, session)
        try {
            val result = ArrayList<NormalizedValue>()
            val iterator = values.iterator()
            while (iterator.hasNext()) {
                if (result.size == limits.maxCollectionSize) {
                    rejectBudget(path, QueryRejectionCode.COLLECTION_LIMIT_EXCEEDED)
                }
                val index = result.size
                result += snapshotValue(iterator.next(), path.index(index), depth + 1, session, budget)
            }
            return NormalizedValue.ListValue(result)
        } finally {
            session.active.remove(values)
        }
    }

    private fun snapshotArray(
        values: Array<*>,
        path: QueryRejectionPath,
        depth: Int,
        session: ValueSession,
        budget: AdmissionBudget,
    ): NormalizedValue.ListValue {
        if (values.size > limits.maxCollectionSize) {
            rejectBudget(path, QueryRejectionCode.COLLECTION_LIMIT_EXCEEDED)
        }
        enterValue(values, path, session)
        try {
            return NormalizedValue.ListValue(
                values.mapIndexed { index, value ->
                    snapshotValue(value, path.index(index), depth + 1, session, budget)
                },
            )
        } finally {
            session.active.remove(values)
        }
    }

    private fun snapshotMap(
        values: Map<*, *>,
        path: QueryRejectionPath,
        depth: Int,
        session: ValueSession,
        budget: AdmissionBudget,
    ): NormalizedValue.ObjectValue {
        enterValue(values, path, session)
        try {
            val result = LinkedHashMap<String, NormalizedValue>()
            val iterator = values.entries.iterator()
            var entryCount = 0
            while (iterator.hasNext()) {
                if (entryCount == limits.maxObjectFields) {
                    rejectBudget(path, QueryRejectionCode.OBJECT_LIMIT_EXCEEDED)
                }
                val entry = iterator.next()
                entryCount++
                val key = entry.key
                if (key !is String) {
                    rejectInvalid(path, QueryRejectionCode.INVALID_VALUE_TYPE)
                }
                val keyPath = path.key(key)
                budget.consumeString(key, keyPath)
                if (result.containsKey(key)) {
                    rejectInvalid(keyPath, QueryRejectionCode.DUPLICATE_OBJECT_KEY)
                }
                result[key] = snapshotValue(entry.value, keyPath, depth + 1, session, budget)
            }
            return NormalizedValue.ObjectValue(result)
        } finally {
            session.active.remove(values)
        }
    }

    private fun enterValue(value: Any, path: QueryRejectionPath, session: ValueSession) {
        if (session.active.put(value, Unit) != null) {
            rejectInvalid(path, QueryRejectionCode.CYCLIC_INPUT)
        }
    }

    private fun rejectInvalid(
        path: QueryRejectionPath,
        code: QueryRejectionCode,
        cause: Throwable? = null,
    ): Nothing = rejectQuery(QueryRejectionCategory.INVALID_QUERY, path, code, cause)

    private fun rejectBudget(path: QueryRejectionPath, code: QueryRejectionCode): Nothing =
        rejectQuery(QueryRejectionCategory.BUDGET_EXCEEDED, path, code)

    private class ValueSession {
        val active: IdentityHashMap<Any, Unit> = IdentityHashMap()
    }
}
