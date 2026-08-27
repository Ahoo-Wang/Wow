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

import me.ahoo.wow.query.schema.QueryStorageType
import org.bson.Document

internal data class MongoStorageSchema(
    val types: Set<QueryStorageType>?,
    val itemTypes: Set<QueryStorageType>?,
) {
    fun union(other: MongoStorageSchema) = MongoStorageSchema(
        types = types.unionConstraint(other.types),
        itemTypes = itemTypes.unionConstraint(other.itemTypes),
    )

    fun intersect(other: MongoStorageSchema) = MongoStorageSchema(
        types = types.intersectConstraint(other.types),
        itemTypes = itemTypes.intersectConstraint(other.itemTypes),
    )
}

internal fun Document?.storageSchemas(): Map<String, MongoStorageSchema> {
    if (this == null) return emptyMap()
    return collectStorageSchemas(path = null, includeType = false)
}

internal fun Document.validatorSchema(): Document? = document("options")
    ?.document("validator")
    ?.document("\$jsonSchema")

private fun Document.collectStorageSchemas(
    path: String?,
    includeType: Boolean,
): Map<String, MongoStorageSchema> = buildMap {
    if (includeType && path != null) {
        val types = storageTypes()
        val itemTypes = itemStorageTypes()
        if (types != null || itemTypes != null) {
            put(path, MongoStorageSchema(types, itemTypes))
        }
    }
    document("properties")?.forEach { (name, child) ->
        (child as? Document)?.let {
            mergeConjunctive(it.collectStorageSchemas(path.child(name), includeType = true))
        }
    }
    document("items")?.let {
        mergeConjunctive(it.collectStorageSchemas(path, includeType = false))
    }
    listOf("anyOf", "oneOf").forEach { key ->
        mergeConjunctive(compositionSchemas(key).mergeAlternatives(path))
    }
    compositionSchemas("allOf").forEach {
        mergeConjunctive(it.collectStorageSchemas(path, includeType = false))
    }
}

private fun Document.compositionSchemas(key: String): List<Document> =
    (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()

private fun List<Document>.mergeAlternatives(path: String?): Map<String, MongoStorageSchema> =
    buildMap {
        val alternatives = this@mergeAlternatives
            .filter { it.canContainChildren() }
            .map { it.collectStorageSchemas(path, includeType = false) }
        alternatives.flatMapTo(linkedSetOf()) { it.keys }.forEach { field ->
            val storage = alternatives.map { it[field] }
            put(
                field,
                if (storage.any { it == null }) {
                    MongoStorageSchema(types = null, itemTypes = null)
                } else {
                    storage.filterNotNull().reduce(MongoStorageSchema::union)
                },
            )
        }
    }

private fun Document.canContainChildren(): Boolean = storageTypes()?.any {
    it.value in OBJECT_TYPES || it.value in ARRAY_TYPES
} != false

private fun MutableMap<String, MongoStorageSchema>.mergeConjunctive(
    other: Map<String, MongoStorageSchema>,
) {
    other.forEach { (field, storage) -> merge(field, storage, MongoStorageSchema::intersect) }
}

private fun Document.storageTypes(): Set<QueryStorageType>? {
    val constraints = buildList {
        directStorageTypes()?.let(::add)
        listOf("anyOf", "oneOf").forEach { key ->
            unionStorageTypes(key)?.let(::add)
        }
        intersectionStorageTypes("allOf")?.let(::add)
    }
    return constraints.reduceOrNull(Set<QueryStorageType>::intersect)
}

private fun Document.directStorageTypes(): Set<QueryStorageType>? =
    when (val declared = this["bsonType"]) {
        is String -> setOfNotNull(declared.takeUnless { it == "null" }?.let(::QueryStorageType))
        is Iterable<*> -> declared.filterIsInstance<String>()
            .filter { it != "null" }
            .mapTo(linkedSetOf(), ::QueryStorageType)
        else -> null
    }

private fun Document.unionStorageTypes(key: String): Set<QueryStorageType>? {
    if (!containsKey(key)) return null
    val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
    if (schemas.isEmpty()) return emptySet()
    val types = schemas.map { it.storageTypes() }
    if (types.any { it == null }) return null
    return types.filterNotNull().flatten().toSet()
}

private fun Document.intersectionStorageTypes(key: String): Set<QueryStorageType>? {
    if (!containsKey(key)) return null
    val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
    if (schemas.isEmpty()) return emptySet()
    return schemas.mapNotNull { it.storageTypes() }
        .reduceOrNull(Set<QueryStorageType>::intersect)
}

private fun Document.itemStorageTypes(): Set<QueryStorageType>? {
    val constraints = buildList {
        document("items")?.storageTypes()?.let(::add)
        listOf("anyOf", "oneOf").forEach { key ->
            unionItemStorageTypes(key)?.let(::add)
        }
        intersectionItemStorageTypes("allOf")?.let(::add)
    }
    return constraints.reduceOrNull(Set<QueryStorageType>::intersect)
}

private fun Document.unionItemStorageTypes(key: String): Set<QueryStorageType>? {
    if (!containsKey(key)) return null
    val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
    val types = mutableListOf<Set<QueryStorageType>>()
    for (schema in schemas) {
        val storageTypes = schema.storageTypes()
        if (storageTypes == null || storageTypes.any { it.value == "array" }) {
            types += schema.itemStorageTypes() ?: return null
        }
    }
    return types.takeIf { it.isNotEmpty() }?.flatten()?.toSet()
}

private fun Document.intersectionItemStorageTypes(key: String): Set<QueryStorageType>? {
    if (!containsKey(key)) return null
    val schemas = (this[key] as? Iterable<*>)?.filterIsInstance<Document>().orEmpty()
    return schemas.mapNotNull { it.itemStorageTypes() }
        .reduceOrNull(Set<QueryStorageType>::intersect)
}

private fun String?.child(name: String): String = if (this == null) name else "$this.$name"

private fun Document.document(key: String): Document? = this[key] as? Document

private fun <T> Set<T>?.unionConstraint(other: Set<T>?): Set<T>? = when {
    this == null || other == null -> null
    else -> this + other
}

private fun <T> Set<T>?.intersectConstraint(other: Set<T>?): Set<T>? = when {
    this == null -> other
    other == null -> this
    else -> intersect(other)
}

internal val INTEGRAL_TYPES = setOf("int", "long")
internal val NUMERIC_TYPES = INTEGRAL_TYPES + setOf("double", "decimal", "number")
internal val DATE_TYPES = setOf("date", "timestamp")
internal val STRING_TYPES = setOf("string")
internal val BOOLEAN_TYPES = setOf("bool")
internal val OBJECT_TYPES = setOf("object")
internal val ARRAY_TYPES = setOf("array")
