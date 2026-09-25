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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import tools.jackson.databind.JsonNode

/**
 * Type inference for the query [Catalog][QuerySchemaCatalog]: reports how a JVM type serializes, as raw facts.
 *
 * A model source knows JSON shapes and annotations, not queries: it reports paths, types, nullability, enums, format
 * hints and the annotations found on each member. What those facts mean for a query model (sensitivity and masking,
 * time encoding, which types form a model, how event payloads combine) is decided by [InferredQuerySchemaSource].
 */
fun interface QueryModelSource {
    /** The serialized shape of [type]. Failures propagate; a source must not return partial facts. */
    fun describe(type: Class<*>): QueryTypeFact
}

/**
 * One value of a serialized type.
 *
 * [nullable] is `null` when the source cannot tell; [required] is `null` outside an object property. [formats] are
 * JSON Schema `format` hints such as `date-time`. [properties] are named by their serialized names, even names that
 * are not valid query path segments; [additionalProperties] is the value of every other key of an open object (a
 * map), or `null` for none. [member] is the field or getter that declares this value, `null` for a nested value, and
 * [omitted] lists members below this value that the source did not expand, such as a recursive type's members.
 */
class QueryTypeFact(
    val kind: QueryValueKind,
    valueTypes: Set<QueryValueType> = emptySet(),
    val nullable: Boolean? = null,
    val required: Boolean? = null,
    enumValues: List<JsonNode>? = null,
    val title: String? = null,
    val description: String? = null,
    formats: Set<String> = emptySet(),
    properties: Map<String, QueryTypeFact> = emptyMap(),
    val items: QueryTypeFact? = null,
    val additionalProperties: QueryTypeFact? = null,
    alternatives: List<QueryTypeFact> = emptyList(),
    val member: QueryMemberFact? = null,
    omitted: List<QueryMemberFact> = emptyList(),
) {
    val valueTypes: Set<QueryValueType> = java.util.Collections.unmodifiableSet(LinkedHashSet(valueTypes))
    val enumValues: List<JsonNode>? = enumValues?.let { values -> java.util.List.copyOf(values.map { it.deepCopy() }) }
    val formats: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(formats))
    val properties: Map<String, QueryTypeFact> = java.util.Collections.unmodifiableMap(LinkedHashMap(properties))
    val alternatives: List<QueryTypeFact> = java.util.List.copyOf(alternatives)
    val omitted: List<QueryMemberFact> = java.util.List.copyOf(omitted)
}

/**
 * A serialized member: its JVM [type] and its effective [annotations], those declared on the field, getter or
 * inherited property it serializes from. Meta-annotations are not expanded.
 *
 * [valueType] is the type the member's values are declared with: the declared class, which differs from [type] for a
 * Kotlin value class (erased to its underlying type on the JVM), or the element class of a collection or array.
 */
class QueryMemberFact(
    val name: String,
    val type: Class<*>,
    annotations: List<Annotation>,
    val valueType: Class<*> = type,
) {
    val annotations: List<Annotation> = java.util.List.copyOf(annotations)

    override fun toString(): String = "QueryMemberFact(name=$name, type=${type.name})"
}
