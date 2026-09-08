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

package me.ahoo.wow.mongo.query.schema

import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
import me.ahoo.wow.query.schema.QueryStorageType
import org.bson.Document

internal data class MongoStorageSchema(val types: Set<QueryStorageType>?, val uncertain: Boolean = false) {
    fun union(other: MongoStorageSchema) = MongoStorageSchema(
        if (types == null || other.types == null) null else types + other.types,
        uncertain || other.uncertain || types == null || other.types == null,
    )

    fun intersect(other: MongoStorageSchema) = MongoStorageSchema(
        when { types == null -> other.types
            other.types == null -> types
            else -> types.intersect(other.types) },
        (uncertain || other.uncertain) && types == null && other.types == null,
    )
}

internal fun Document?.storageSchemas(): Map<QueryPathTemplate, MongoStorageSchema> =
    this?.collectStorageSchemas(emptyList()).orEmpty()

internal fun Document.validatorSchema(): Document? = document("options")
    ?.document("validator")?.document("\$jsonSchema")

private fun Document.collectStorageSchemas(path: List<QueryPathSegment>): Map<QueryPathTemplate, MongoStorageSchema> = buildMap {
    val template = QueryPathTemplate(path)
    put(template, MongoStorageSchema(directStorageTypes()))
    document("properties")?.forEach { (name, child) ->
        (child as? Document)?.let { mergeConjunctive(it.collectStorageSchemas(path + QueryPathSegment.Property(name))) }
    }
    document("additionalProperties")?.let {
        val slot = path.count { it is QueryPathSegment.Key }
        mergeConjunctive(it.collectStorageSchemas(path + QueryPathSegment.Key(slot)))
    }
    document("items")?.let { mergeConjunctive(it.collectStorageSchemas(path + QueryPathSegment.Item)) }
    listOf("anyOf", "oneOf").forEach { key ->
        val alternatives = compositionSchemas(key).map { it.collectStorageSchemas(path) }
        if (alternatives.isNotEmpty()) {
            val merged = alternatives.flatMapTo(linkedSetOf()) { it.keys }.associateWith { field ->
                alternatives.filter { alternative ->
                    val next = field.segments.getOrNull(template.segments.size)
                    val parentTypes = alternative[template]?.types
                    next == null || parentTypes == null || parentTypes.any {
                        it.value in if (next == QueryPathSegment.Item) ARRAY_TYPES else OBJECT_TYPES
                    }
                }.map { it[field] ?: MongoStorageSchema(null, uncertain = true) }
                    .reduceOrNull(MongoStorageSchema::union) ?: MongoStorageSchema(emptySet())
            }
            mergeConjunctive(merged)
        }
    }
    compositionSchemas("allOf").forEach { mergeConjunctive(it.collectStorageSchemas(path)) }
}

private fun MutableMap<QueryPathTemplate, MongoStorageSchema>.mergeConjunctive(other: Map<QueryPathTemplate, MongoStorageSchema>) {
    other.forEach { (field, value) -> merge(field, value, MongoStorageSchema::intersect) }
}

/** Named map keys override defaults. Array steps remain explicit; sparse native arrays are visible to admission. */
internal fun Map<QueryPathTemplate, MongoStorageSchema>.storageAt(path: QueryPathTemplate): MongoStorageSchema? {
    this[path]?.let { return it }
    return entries.filter { (candidate, _) ->
        candidate.segments.size == path.segments.size && candidate.segments.zip(path.segments).withIndex().all { (index, pair) ->
            val (native, logical) = pair
            native == logical || native is QueryPathSegment.Key &&
                (
                    logical is QueryPathSegment.Key || logical is QueryPathSegment.Property &&
                        !containsKey(QueryPathTemplate(path.segments.take(index + 1)))
                    )
        }
    }.maxByOrNull { (candidate, _) -> candidate.segments.count { it is QueryPathSegment.Property } }?.value
}

private fun Document.compositionSchemas(key: String): List<Document> =
    (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()

private fun Document.directStorageTypes(): Set<QueryStorageType>? = when (val declared = this["bsonType"]) {
    is String -> declared.storageTypes()
    is Iterable<*> -> declared.filterIsInstance<String>().flatMapTo(linkedSetOf()) { it.storageTypes() }
    else -> null
}

/** BSON's number alias denotes all concrete numeric types, so intersections operate on values, not spelling. */
private fun String.storageTypes(): Set<QueryStorageType> = when (this) {
    "null" -> emptySet()
    "number" -> (NUMERIC_TYPES - "number").mapTo(linkedSetOf(), ::QueryStorageType)
    else -> setOf(QueryStorageType(this))
}

private fun Document.document(key: String): Document? = this[key] as? Document

internal val INTEGRAL_TYPES = setOf("int", "long")
internal val NUMERIC_TYPES = INTEGRAL_TYPES + setOf("double", "decimal", "number")
internal val DATE_TYPES = setOf("date", "timestamp")
internal val STRING_TYPES = setOf("string")
internal val BOOLEAN_TYPES = setOf("bool")
internal val OBJECT_TYPES = setOf("object")
internal val ARRAY_TYPES = setOf("array")
