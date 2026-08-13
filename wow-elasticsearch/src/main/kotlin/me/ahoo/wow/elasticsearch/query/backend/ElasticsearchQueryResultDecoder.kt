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
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.serialization.convert
import java.math.BigInteger
import java.time.Instant
import java.util.Base64

internal class ElasticsearchQueryResultDecoder(
    private val binding: ElasticsearchQueryFieldBinding,
) {
    fun <R : Any> decode(
        source: Map<String, Any?>?,
        shape: QueryPlanResultShape,
        projection: Map<LogicalField, String>,
    ): R {
        val sanitized = source?.let(ElasticsearchQueryPresenceEncoder::strip) ?: resultInvalid()
        val flatValues = LinkedHashMap<LogicalField, Any?>(projection.size)
        projection.forEach { (logical, physical) ->
            val resolved = resolve(sanitized, physical.split('.'))
            flatValues[logical] = materialize(sanitized, logical, physical, resolved)
        }
        @Suppress("UNCHECKED_CAST")
        return when (shape) {
            is QueryPlanResultShape.Dynamic -> ImmutableDynamicDocument.copyOf(
                flatValues.entries.associateTo(LinkedHashMap()) { (field, value) ->
                    field.value to externalize(value)
                },
            ) as R
            is QueryPlanResultShape.Typed -> decodeTyped(shape, flatValues)
            QueryPlanResultShape.Count -> error("Count plan cannot decode search hit source.")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <R : Any> decodeTyped(
        shape: QueryPlanResultShape.Typed,
        flatValues: Map<LogicalField, Any?>,
    ): R {
        val structured = LinkedHashMap<String, Any?>()
        flatValues.forEach { (field, value) -> insert(structured, field, value) }
        return try {
            structured.convert(shape.resultType) as R
        } catch (error: QueryException) {
            throw error
        } catch (_: Exception) {
            resultInvalid()
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
            is List<*> -> source.map { element ->
                if (element == null) NULL_COLLECTION_ELEMENT else resolve(element, segments, index)
            }
            null -> MISSING
            else -> INVALID
        }
    }

    private fun materialize(
        source: Map<String, Any?>,
        logical: LogicalField,
        physical: String,
        resolved: Any?,
    ): Any? {
        val schema = binding.schema(logical)
        val segments = logical.value.split('.')
        val collectionDepth = collectionDepth(segments, segments.size)
        return materializeValue(source, schema, segments, physical, resolved, collectionDepth, emptyList())
    }

    private fun materializeValue(
        source: Map<String, Any?>,
        schema: QueryFieldSchema,
        segments: List<String>,
        physical: String,
        value: Any?,
        remainingCollections: Int,
        indices: List<Int>,
    ): Any? = when {
        value === INVALID || value === NULL_COLLECTION_ELEMENT -> resultInvalid()
        value === MISSING || value == null -> materializeAbsent(
            source,
            schema,
            segments,
            physical,
            remainingCollections,
            indices,
        )
        remainingCollections > 0 -> (value as? List<*>)?.mapIndexed { index, nested ->
            materializeValue(
                source,
                schema,
                segments,
                physical,
                nested,
                remainingCollections - 1,
                indices + index,
            )
        } ?: resultInvalid()
        value is List<*> -> resultInvalid()
        else -> normalizeScalar(schema, value)
    }

    private fun materializeAbsent(
        source: Map<String, Any?>,
        schema: QueryFieldSchema,
        segments: List<String>,
        physical: String,
        remainingCollections: Int,
        indices: List<Int>,
    ): Any? {
        val absentAncestor = absentAncestor(source, segments, physical, indices)
        return when {
            absentAncestor !== NO_ABSENT_ANCESTOR -> absentAncestor
            isNullableProperty(schema, remainingCollections) -> null
            else -> resultInvalid()
        }
    }

    private fun isNullableProperty(schema: QueryFieldSchema, remainingCollections: Int): Boolean =
        schema.nullable && (schema.collectionKind == QueryCollectionKind.NONE || remainingCollections > 0)

    private fun normalizeScalar(schema: QueryFieldSchema, value: Any): Any? = when (schema.valueKind) {
        QueryFieldValueKind.BOOLEAN -> normalizeIf(value, value is Boolean)
        QueryFieldValueKind.INTEGER -> normalizeIf(value, value.isIntegerNumber())
        QueryFieldValueKind.DECIMAL -> normalizeIf(value, value.isFiniteDecimal())
        QueryFieldValueKind.STRING,
        QueryFieldValueKind.ENUM,
        -> normalizeIf(value, value is String)
        QueryFieldValueKind.TIME -> normalizeTime(value, schema.system)
        QueryFieldValueKind.BINARY -> normalizeBinary(value)
        QueryFieldValueKind.OBJECT,
        QueryFieldValueKind.MAP,
        -> normalizeIf(value, value is Map<*, *>)
    }

    private fun normalizeIf(value: Any, valid: Boolean): Any? {
        if (!valid) {
            resultInvalid()
        }
        return try {
            normalize(value)
        } catch (error: QueryException) {
            throw error
        } catch (_: RuntimeException) {
            resultInvalid()
        }
    }

    private fun Any.isFiniteDecimal(): Boolean = when (this) {
        is Double -> isFinite()
        is Float -> isFinite()
        is Number -> true
        else -> false
    }

    private fun normalizeBinary(value: Any): ByteArray = try {
        when (value) {
            is String -> Base64.getDecoder().decode(value)
            is ByteArray -> value.copyOf()
            else -> resultInvalid()
        }
    } catch (_: IllegalArgumentException) {
        resultInvalid()
    }

    private fun normalizeTime(value: Any, system: Boolean): Instant = when {
        system && value.isIntegerNumber() -> normalizeEpochMillis(value)
        !system && value is String -> runCatching { Instant.parse(value) }.getOrElse { resultInvalid() }
        else -> resultInvalid()
    }

    private fun normalizeEpochMillis(value: Any): Instant {
        val epochMillis = try {
            if (value is BigInteger) value.longValueExact() else (value as Number).toLong()
        } catch (_: RuntimeException) {
            resultInvalid()
        }
        return Instant.ofEpochMilli(epochMillis)
    }

    private fun Any?.isIntegerNumber(): Boolean =
        this is Byte || this is Short || this is Int || this is Long || this is BigInteger

    private fun absentAncestor(
        source: Map<String, Any?>,
        segments: List<String>,
        requestedPhysical: String,
        indices: List<Int>,
    ): Any {
        ancestorLoop@ for (segmentCount in 1 until segments.size) {
            val ancestor = LogicalField(segments.take(segmentCount).joinToString("."))
            if (!binding.contains(ancestor)) {
                continue
            }
            val ancestorSchema = binding.schema(ancestor)
            val ancestorPhysical = binding.source(ancestor)
            if (!requestedPhysical.startsWith("$ancestorPhysical.")) {
                continue
            }
            var value = resolve(source, ancestorPhysical.split('.'))
            val containingCollectionDepth = collectionDepth(segments, segmentCount - 1)
            for (depth in 0 until containingCollectionDepth) {
                val elementIndex = indices.getOrNull(depth) ?: continue@ancestorLoop
                value = (value as? List<*>)?.getOrNull(elementIndex) ?: MISSING
            }
            if (value === MISSING || value == null) {
                if (!ancestorSchema.nullable) {
                    resultInvalid()
                }
                return ABSENT_NULLABLE_ANCESTOR to ancestor.value
            }
        }
        return NO_ABSENT_ANCESTOR
    }

    private fun collectionDepth(segments: List<String>, segmentCount: Int): Int =
        (1..segmentCount).count { endIndex ->
            val field = LogicalField(segments.take(endIndex).joinToString("."))
            binding.contains(field) && binding.schema(field).collectionKind != QueryCollectionKind.NONE
        }

    private fun insert(target: MutableMap<String, Any?>, logical: LogicalField, value: Any?) {
        val segments = logical.value.split('.')
        insert(target, segments, 0, "", value)
    }

    private fun insert(
        target: MutableMap<String, Any?>,
        segments: List<String>,
        index: Int,
        parentPath: String,
        value: Any?,
    ) {
        val segment = segments[index]
        if (index == segments.lastIndex) {
            target[segment] = value
            return
        }
        val currentPath = if (parentPath.isEmpty()) segment else "$parentPath.$segment"
        if (absentAncestorPath(value) == currentPath) {
            target[segment] = null
            return
        }
        val field = binding.schema(LogicalField(currentPath))
        if (field.collectionKind == QueryCollectionKind.OBJECT) {
            insertCollection(target, segment, segments, index, currentPath, value)
        } else {
            val child = target[segment]?.let(::mutableStringMap) ?: LinkedHashMap()
            insert(child, segments, index + 1, currentPath, value)
            target[segment] = child
        }
    }

    private fun insertCollection(
        target: MutableMap<String, Any?>,
        segment: String,
        segments: List<String>,
        index: Int,
        currentPath: String,
        value: Any?,
    ) {
        val projected = value as? List<*> ?: resultInvalid()
        val elements = when (val existing = target[segment]) {
            null -> MutableList(projected.size) { LinkedHashMap<String, Any?>() }
            is List<*> -> existing.map(::mutableStringMap).toMutableList()
            else -> resultInvalid()
        }
        if (elements.size != projected.size) {
            resultInvalid()
        }
        projected.forEachIndexed { elementIndex, nestedValue ->
            insert(elements[elementIndex], segments, index + 1, currentPath, nestedValue)
        }
        target[segment] = elements
    }

    private fun mutableStringMap(value: Any?): LinkedHashMap<String, Any?> {
        val source = value as? Map<*, *> ?: resultInvalid()
        return source.entries.associateTo(LinkedHashMap()) { (key, nested) ->
            (key as? String ?: resultInvalid()) to nested
        }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (key, nested) ->
            (key as? String ?: resultInvalid()) to normalize(nested)
        }
        is List<*> -> value.map(::normalize)
        is ByteArray -> value.copyOf()
        else -> value
    }

    private fun externalize(value: Any?): Any? = when {
        absentAncestorPath(value) != null -> null
        value is List<*> -> value.map(::externalize)
        else -> value
    }

    private fun absentAncestorPath(value: Any?): String? =
        (value as? Pair<*, *>)?.takeIf { marker -> marker.first === ABSENT_NULLABLE_ANCESTOR }?.second as? String

    private fun resultInvalid(): Nothing = throw QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.EXECUTION,
        QueryErrorReason.RESULT_INVALID,
    )

    private companion object {
        val MISSING: Any = Any()
        val INVALID: Any = Any()
        val NULL_COLLECTION_ELEMENT: Any = Any()
        val ABSENT_NULLABLE_ANCESTOR: Any = Any()
        val NO_ABSENT_ANCESTOR: Any = Any()
    }
}
