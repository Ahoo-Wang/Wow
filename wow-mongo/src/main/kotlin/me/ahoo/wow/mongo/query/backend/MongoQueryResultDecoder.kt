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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.ImmutableDynamicDocument
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.schema.QueryCollectionKind
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryFieldValueKind
import me.ahoo.wow.serialization.convert
import org.bson.BsonBinary
import org.bson.Document
import org.bson.types.Binary
import org.bson.types.Decimal128
import java.time.Instant
import java.util.Date

internal class MongoQueryResultDecoder(
    private val binding: MongoQueryFieldBinding
) {
    fun <R : Any> decode(
        source: Document,
        shape: QueryPlanResultShape,
        projection: Map<LogicalField, String>
    ): R {
        val flatValues = LinkedHashMap<LogicalField, Any?>(projection.size)
        projection.forEach { (logical, physical) ->
            val resolved = resolve(source, physical.split('.'))
            flatValues[logical] = materialize(source, logical, resolved)
        }
        @Suppress("UNCHECKED_CAST")
        return when (shape) {
            is QueryPlanResultShape.Dynamic -> ImmutableDynamicDocument.copyOf(
                flatValues.entries.associateTo(LinkedHashMap()) { (field, value) -> field.value to value }
            ) as R
            is QueryPlanResultShape.Typed -> {
                val structured = LinkedHashMap<String, Any?>()
                flatValues.forEach { (field, value) -> insert(structured, field, value) }
                structured.convert(shape.resultType) as R
            }
            QueryPlanResultShape.Count -> error("Count plans do not decode result documents.")
        }
    }

    private fun resolve(source: Any?, segments: List<String>, index: Int = 0): Any? {
        if (index == segments.size) {
            return source
        }
        return when (source) {
            is Map<*, *> -> {
                val segment = segments[index]
                if (source.containsKey(segment)) {
                    resolve(source[segment], segments, index + 1)
                } else {
                    MISSING
                }
            }

            is List<*> -> source.map { element -> resolve(element, segments, index) }
            null -> MISSING
            else -> INVALID
        }
    }

    private fun materialize(source: Document, logical: LogicalField, resolved: Any?): Any? {
        val schema = binding.schema(logical)
        val segments = logical.value.split('.')
        val collectionDepth = collectionDepth(segments, segments.size)
        return materializeValue(source, schema, segments, resolved, collectionDepth, emptyList())
    }

    private fun materializeValue(
        source: Document,
        schema: QueryFieldSchema,
        segments: List<String>,
        value: Any?,
        remainingCollections: Int,
        indices: List<Int>
    ): Any? = when {
        value === INVALID -> resultInvalid()
        value === MISSING || value == null ->
            if (schema.nullable || hasAbsentNullableAncestor(source, segments, indices)) null else resultInvalid()
        remainingCollections > 0 -> (value as? List<*>)
            ?.mapIndexed { index, nested ->
                materializeValue(source, schema, segments, nested, remainingCollections - 1, indices + index)
            } ?: resultInvalid()
        value is List<*> -> resultInvalid()
        else -> normalizeScalar(schema, value)
    }

    private fun normalizeScalar(schema: QueryFieldSchema, value: Any): Any? = when {
        schema.valueKind == QueryFieldValueKind.OBJECT || schema.valueKind == QueryFieldValueKind.MAP ->
            if (value is Map<*, *>) normalize(value) else resultInvalid()
        schema.valueKind == QueryFieldValueKind.TIME -> normalizeTime(value, schema.system)
        value is Map<*, *> -> resultInvalid()
        else -> normalize(value)
    }

    private fun hasAbsentNullableAncestor(
        source: Document,
        segments: List<String>,
        indices: List<Int>
    ): Boolean = (1 until segments.size).any { segmentCount ->
        val ancestor = LogicalField(segments.take(segmentCount).joinToString("."))
        if (!binding.contains(ancestor) || !binding.schema(ancestor).nullable) {
            return@any false
        }
        var value = resolve(source, binding.physical(ancestor).split('.'))
        val ancestorCollectionDepth = collectionDepth(segments, segmentCount)
        repeat(ancestorCollectionDepth) { depth ->
            value = (value as? List<*>)?.getOrNull(indices.getOrNull(depth) ?: return@any false) ?: MISSING
        }
        value === MISSING || value == null
    }

    private fun collectionDepth(segments: List<String>, segmentCount: Int): Int =
        (1..segmentCount).count { endIndex ->
            val field = LogicalField(segments.take(endIndex).joinToString("."))
            binding.contains(field) && binding.schema(field).collectionKind != QueryCollectionKind.NONE
        }

    private fun normalizeTime(value: Any?, system: Boolean): Any? = when {
        value == null -> null
        system && value is Number -> Instant.ofEpochMilli(value.toLong())
        !system && value is String -> runCatching { Instant.parse(value) }.getOrElse { resultInvalid() }
        else -> resultInvalid()
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
        value: Any?
    ) {
        val segment = segments[index]
        if (index == segments.lastIndex) {
            target[segment] = value
            return
        }
        val currentPath = if (parentPath.isEmpty()) segment else "$parentPath.$segment"
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
        value: Any?
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
        is Document -> value.entries.associateTo(LinkedHashMap()) { (key, nested) -> key to normalize(nested) }
        is Map<*, *> -> value.entries.associateTo(LinkedHashMap()) { (key, nested) ->
            require(key is String) { "Mongo result document keys must be strings." }
            key to normalize(nested)
        }
        is List<*> -> value.map(::normalize)
        is Decimal128 -> value.bigDecimalValue()
        is Date -> value.toInstant()
        is Binary -> value.data.copyOf()
        is BsonBinary -> value.data.copyOf()
        else -> value
    }

    private fun resultInvalid(): Nothing = throw QueryException(
        QueryErrorCode.RESULT_VALIDATION_FAILED,
        QueryStage.EXECUTION,
        QueryErrorReason.RESULT_INVALID
    )

    private companion object {
        val MISSING: Any = Any()
        val INVALID: Any = Any()
    }
}
