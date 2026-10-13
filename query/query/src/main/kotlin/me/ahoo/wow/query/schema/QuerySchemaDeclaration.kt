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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import tools.jackson.databind.JsonNode

/** A fact a declaration states, or leaves to a source of lower priority. */
sealed interface DeclarationValue<out T> {
    data object Unset : DeclarationValue<Nothing>

    data class Set<T>(val value: T) : DeclarationValue<T>
}

/** The read model of one aggregate type a schema is compiled for. */
data class QuerySchemaContext(
    val aggregateType: AggregateType,
    val model: QueryModel,
)

/** A [declaration] of [context]'s model, as [querySchemaRegistration] builds it. */
data class QuerySchemaRegistration(
    val context: QuerySchemaContext,
    val declaration: QuerySchemaDeclaration,
)

/** What one source declares about a model's fields, by logical field. */
data class QuerySchemaDeclaration(
    val fields: Map<QueryField, QueryFieldDeclaration>,
)

/**
 * What one source declares about one logical value, in the capability descriptor's vocabulary; each fact may be left
 * [Unset][DeclarationValue.Unset] for a source of lower priority to state. Sensitivity and masking are declared by
 * 6.1c's sources.
 */
data class QueryFieldDeclaration(
    val title: DeclarationValue<String?> = DeclarationValue.Unset,
    val description: DeclarationValue<String?> = DeclarationValue.Unset,
    val enumValues: DeclarationValue<List<JsonNode>?> = DeclarationValue.Unset,
    /** What each enum value means, by value; values without a description are absent. */
    val enumDescriptions: DeclarationValue<Map<JsonNode, String>> = DeclarationValue.Unset,
    val valueTypes: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset,
    val nullable: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val required: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val kind: DeclarationValue<QueryValueKind> = DeclarationValue.Unset,
    val properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = DeclarationValue.Unset,
    val items: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
    val additionalProperties: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
    val alternatives: DeclarationValue<List<QueryFieldDeclaration>> = DeclarationValue.Unset,
    val semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    /**
     * The discriminator value of the variant this value is, when it is one alternative of a model's variant payload,
     * such as an event's type.
     */
    val variant: DeclarationValue<String?> = DeclarationValue.Unset,
    /** Other logical paths that name this value; admission replaces them with this value's path. */
    val aliases: DeclarationValue<Set<QueryField>> = DeclarationValue.Unset,
    /** Set when the field is kept only for existing callers. */
    val deprecated: DeclarationValue<QueryDeprecation?> = DeclarationValue.Unset,
)

/**
 * Where a model's field declarations come from (design §5.2): the Manifest's state schema (`$defs` plus `x-wow-*`,
 * work package 6.1c), declarations registered in code ([BeanQuerySchemaSource]), or any other source a host adds. The
 * Catalog merges the declarations of every source by [priority], a higher one stating a fact over a lower one.
 */
interface QuerySchemaSource {
    val priority: Int

    /** The declarations of [context]'s model this source holds. */
    suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration>

    /** [load], re-reading what this source caches. */
    suspend fun refresh(context: QuerySchemaContext): List<QuerySchemaDeclaration> = load(context)
}

/** The priorities of the built-in sources; a host's own source picks one between or above them. */
object QuerySchemaSourcePriority {
    /** The Manifest's state schema (6.1c), in place of 9.x's runtime inference. */
    const val MANIFEST = 100

    /** Declarations registered in code. */
    const val BEAN = 300
}

internal data class PrioritizedQuerySchemaDeclaration(
    val priority: Int,
    val declaration: QuerySchemaDeclaration,
)

internal fun QueryFieldDeclaration.merge(
    higher: QueryFieldDeclaration,
    field: QueryField,
    rejectDifferent: Boolean,
): QueryFieldDeclaration = QueryFieldDeclaration(
    title = title.merge(higher.title, field, "title", rejectDifferent),
    description = description.merge(higher.description, field, "description", rejectDifferent),
    enumValues = enumValues.merge(higher.enumValues, field, "enumValues", rejectDifferent),
    enumDescriptions = enumDescriptions.merge(higher.enumDescriptions, field, "enumDescriptions", rejectDifferent),
    valueTypes = valueTypes.merge(higher.openedValueTypes(this), field, "valueTypes", rejectDifferent),
    nullable = nullable.merge(higher.nullable, field, "nullable", rejectDifferent),
    required = required.merge(higher.required, field, "required", rejectDifferent),
    kind = kind.merge(higher.kind, field, "kind", rejectDifferent),
    properties = mergeProperties(higher, field, rejectDifferent),
    items = items.merge(higher.items, field, "items", rejectDifferent),
    additionalProperties = additionalProperties.merge(
        higher.additionalProperties,
        field,
        "additionalProperties",
        rejectDifferent,
    ),
    alternatives = alternatives.merge(higher.alternatives, field, "alternatives", rejectDifferent),
    semanticType = semanticType.merge(higher.semanticType, field, "semanticType", rejectDifferent),
    variant = variant.merge(higher.variant, field, "variant", rejectDifferent),
    aliases = aliases.merge(higher.aliases, field, "aliases", rejectDifferent),
    deprecated = deprecated.merge(higher.deprecated, field, "deprecated", rejectDifferent),
)

/**
 * The value types of this declaration merged over [lower]. A declaration of kind `OBJECT` over an opaque value
 * (kind `UNKNOWN` with no value type) opens it as an object: the value types become `OBJECT`, which a declaration
 * cannot state itself ([QueryFieldDeclarationBuilder.types] is scalar only). Over any other kind the declared kind
 * alone is merged, so a scalar or array is never turned into an object.
 */
private fun QueryFieldDeclaration.openedValueTypes(
    lower: QueryFieldDeclaration
): DeclarationValue<Set<QueryValueType>> {
    val opensOpaqueValue = valueTypes === DeclarationValue.Unset &&
        kind == DeclarationValue.Set(QueryValueKind.OBJECT) &&
        lower.kind == DeclarationValue.Set(QueryValueKind.UNKNOWN)
    return if (opensOpaqueValue) DeclarationValue.Set(setOf(QueryValueType.OBJECT)) else valueTypes
}

private fun <T> DeclarationValue<T>.merge(
    higher: DeclarationValue<T>,
    field: QueryField,
    leaf: String,
    rejectDifferent: Boolean,
): DeclarationValue<T> {
    if (higher === DeclarationValue.Unset) {
        return this
    }
    if (rejectDifferent && this is DeclarationValue.Set && higher is DeclarationValue.Set) {
        if (value != higher.value) {
            throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
        }
    }
    return higher
}

internal fun <T> DeclarationValue<T>.valueOr(default: T): T = when (this) {
    is DeclarationValue.Set -> value
    DeclarationValue.Unset -> default
}

private fun QueryFieldDeclaration.mergeProperties(
    higher: QueryFieldDeclaration,
    field: QueryField,
    rejectDifferent: Boolean,
): DeclarationValue<Map<String, QueryFieldDeclaration>> {
    val incoming = higher.properties.valueOr(emptyMap())
    val merged = properties.valueOr(emptyMap()).toMutableMap()
    incoming.forEach { (name, child) ->
        val childField = QueryField("${field.path}.$name")
        merged[name] = merged[name]?.merge(child, childField, rejectDifferent) ?: child
    }
    return if (merged.isEmpty() && higher.properties === DeclarationValue.Unset) {
        properties
    } else {
        DeclarationValue.Set(merged)
    }
}

internal fun QueryFieldDeclaration.inferredKind(): QueryValueKind = kind.valueOr(
    when {
        alternatives.valueOr(emptyList()).isNotEmpty() -> QueryValueKind.UNION
        items.valueOr(null) != null -> QueryValueKind.ARRAY
        valueTypes.valueOr(emptySet()) == setOf(QueryValueType.OBJECT) || properties is DeclarationValue.Set ||
            additionalProperties.valueOr(null) != null -> QueryValueKind.OBJECT
        valueTypes.valueOr(emptySet()).isNotEmpty() -> QueryValueKind.SCALAR
        else -> QueryValueKind.UNKNOWN
    },
)
