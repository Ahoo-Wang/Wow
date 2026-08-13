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

@file:JvmSynthetic

package me.ahoo.wow.elasticsearch.query.backend

import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.serialization.convert
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

internal class ElasticsearchQueryResultDecoder(
    private val binding: ElasticsearchQueryFieldBinding,
) {
    fun <R : Any> decode(
        source: Map<String, Any?>?,
        shape: QueryPlanResultShape,
        projection: Map<LogicalField, String>,
    ): R {
        val sanitized = source?.let(ElasticsearchQueryPresenceEncoder::strip) ?: resultInvalid()
        val flat = LinkedHashMap<LogicalField, Any?>(projection.size)
        projection.forEach { (logical, physical) ->
            val schema = binding.schema(logical)
            flat[logical] = validate(
                schema.valueKind,
                schema.nullable,
                resolve(sanitized, physical.split('.')),
            )
        }
        @Suppress("UNCHECKED_CAST")
        return when (shape) {
            is QueryPlanResultShape.Dynamic -> ImmutableDynamicDocument.copyOf(
                flat.entries.associateTo(LinkedHashMap()) { (field, value) -> field.value to value },
            ) as R
            is QueryPlanResultShape.Typed -> {
                val structured = LinkedHashMap<String, Any?>()
                flat.forEach { (field, value) -> insert(structured, field.value.split('.'), value) }
                try {
                    structured.convert(shape.resultType) as R
                } catch (error: QueryException) {
                    throw error
                } catch (_: Exception) {
                    resultInvalid()
                }
            }
            QueryPlanResultShape.Count -> error("Count plan cannot decode search hit source.")
        }
    }

    private fun resolve(source: Any?, segments: List<String>, index: Int = 0): Any? {
        if (index == segments.size) {
            return source
        }
        return when (source) {
            is Map<*, *> -> {
                val segment = segments[index]
                if (source.containsKey(segment)) resolve(source[segment], segments, index + 1) else MISSING
            }
            is List<*> -> source.map { nested -> resolve(nested, segments, index) }
            else -> MISSING
        }
    }

    private fun validate(kind: QueryFieldValueKind, nullable: Boolean, value: Any?): Any? = when {
        value === MISSING -> if (nullable) null else resultInvalid()
        value is List<*> -> value.map { nested -> validate(kind, nullable, nested) }
        value == null -> if (nullable) null else resultInvalid()
        else -> validateScalar(kind, value)
    }

    private fun validateScalar(kind: QueryFieldValueKind, value: Any): Any = when (kind) {
        QueryFieldValueKind.BOOLEAN -> value.takeIf { it is Boolean } ?: resultInvalid()
        QueryFieldValueKind.INTEGER -> value.takeIf { it.isInteger() } ?: resultInvalid()
        QueryFieldValueKind.DECIMAL -> validateDecimal(value)
        QueryFieldValueKind.STRING,
        QueryFieldValueKind.ENUM,
        -> value.takeIf { it is String } ?: resultInvalid()
        QueryFieldValueKind.TIME -> validateTime(value)
        QueryFieldValueKind.BINARY -> (value as? ByteArray)?.copyOf() ?: resultInvalid()
        QueryFieldValueKind.OBJECT,
        QueryFieldValueKind.MAP,
        -> value.takeIf { it is Map<*, *> } ?: resultInvalid()
    }

    private fun validateDecimal(value: Any): Any = when {
        value is BigDecimal -> value
        value is Number && value.isFiniteNumber() -> value
        else -> resultInvalid()
    }

    private fun validateTime(value: Any): Instant = when {
        value is String -> runCatching { Instant.parse(value) }.getOrElse { resultInvalid() }
        value.isInteger() -> Instant.ofEpochMilli((value as Number).toLong())
        else -> resultInvalid()
    }

    private fun Any?.isInteger(): Boolean = this is Byte || this is Short || this is Int || this is Long ||
        this is BigInteger

    private fun Number.isFiniteNumber(): Boolean = when (this) {
        is Double -> isFinite()
        is Float -> isFinite()
        else -> true
    }

    private fun insert(target: MutableMap<String, Any?>, segments: List<String>, value: Any?) {
        var current = target
        segments.dropLast(1).forEach { segment ->
            @Suppress("UNCHECKED_CAST")
            current = current.getOrPut(segment) { LinkedHashMap<String, Any?>() } as? MutableMap<String, Any?>
                ?: resultInvalid()
        }
        current[segments.last()] = value
    }

    private fun resultInvalid(): Nothing = throw QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.EXECUTION,
        QueryErrorReason.RESULT_INVALID,
    )

    private companion object {
        val MISSING: Any = Any()
    }
}
