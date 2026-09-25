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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.configuration.requiredNamedAggregate
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Declares, in code, what inference cannot know about a model's fields; the same vocabulary as a declaration file
 * (see [QuerySchemaDeclarationProperties]).
 */
class QuerySchemaDeclarationBuilder {
    private val fields = linkedMapOf<QueryField, QueryFieldDeclaration>()

    fun field(field: String, block: QueryFieldDeclarationBuilder.() -> Unit) {
        val logicalField = QueryField(field)
        val declaration = QueryFieldDeclarationBuilder().apply(block).build()
        fields[logicalField] = fields[logicalField]
            ?.merge(declaration, logicalField, rejectDifferent = true)
            ?: declaration
    }

    fun build(): QuerySchemaDeclaration = QuerySchemaDeclaration(fields.toMap())
}

/** One field or nested value: its shape (kind, types, nullability, structure) and semantics (enum, time, text). */
class QueryFieldDeclarationBuilder {
    private var kind: DeclarationValue<QueryValueKind> = DeclarationValue.Unset
    private var types: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset
    private var nullable: DeclarationValue<Boolean> = DeclarationValue.Unset
    private val enumValues = mutableListOf<JsonNode>()
    private val enumDescriptions = linkedMapOf<JsonNode, String>()
    private var semantic: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset
    private var description: DeclarationValue<String?> = DeclarationValue.Unset
    private var properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = DeclarationValue.Unset
    private var items: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset
    private var values: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset

    /** `SCALAR`, `OBJECT` or `ARRAY`; implied by [types], [property]/[values] or [items] when not stated. */
    fun kind(value: QueryValueKind) {
        require(value in DECLARABLE_KINDS) { "Declared kind must be one of $DECLARABLE_KINDS." }
        kind = kind.set(value, "kind")
    }

    fun types(vararg value: QueryValueType) {
        require(value.isNotEmpty() && value.all { it != QueryValueType.OBJECT }) { "Declared types must be scalar." }
        types = types.set(value.toSet(), "types")
    }

    fun nullable(value: Boolean) {
        nullable = nullable.set(value, "nullable")
    }

    /** Adds one declared value, with what it means. */
    fun enumValue(value: Any?, description: String? = null) {
        val node = JsonSerializer.valueToTree<JsonNode>(value)
        require(node !in enumValues) { "Declared enum value [$node] is repeated." }
        enumValues.add(node)
        description?.let { enumDescriptions[node] = it }
    }

    fun semantic(value: QuerySemanticType) {
        semantic = semantic.set(value, "semantic")
    }

    fun temporalEpoch(unit: TimeUnit = TimeUnit.MILLISECONDS) {
        semantic(Temporal.Epoch(unit))
    }

    fun temporalFormatted(pattern: String) {
        semantic(Temporal.Formatted(pattern))
    }

    fun description(value: String) {
        description = description.set(value, "description")
    }

    fun property(name: String, block: QueryFieldDeclarationBuilder.() -> Unit) {
        requireQueryPathSegment(name)
        val declaration = QueryFieldDeclarationBuilder().apply(block).build()
        val children = properties.valueOr(emptyMap()).toMutableMap()
        children[name] = children[name]?.merge(declaration, QueryField(name), true) ?: declaration
        properties = DeclarationValue.Set(children)
    }

    /** The element of an array. */
    fun items(block: QueryFieldDeclarationBuilder.() -> Unit) {
        items(QueryFieldDeclarationBuilder().apply(block).build())
    }

    fun items(value: QueryFieldDeclaration) {
        items = items.set(value, "items")
    }

    /** The value of every key of a map. */
    fun values(block: QueryFieldDeclarationBuilder.() -> Unit) {
        values(QueryFieldDeclarationBuilder().apply(block).build())
    }

    fun values(value: QueryFieldDeclaration) {
        values = values.set(value, "values")
    }

    fun build(): QueryFieldDeclaration = QueryFieldDeclaration(
        kind = kind,
        valueTypes = types,
        nullable = nullable,
        enumValues = if (enumValues.isEmpty()) DeclarationValue.Unset else DeclarationValue.Set(enumValues.toList()),
        enumDescriptions = if (enumValues.isEmpty()) {
            DeclarationValue.Unset
        } else {
            DeclarationValue.Set(
                enumDescriptions.toMap()
            )
        },
        semanticType = semantic,
        description = description,
        properties = properties,
        items = items,
        additionalProperties = values,
    )

    private fun <T> DeclarationValue<T>.set(value: T, leaf: String): DeclarationValue<T> {
        if (this is DeclarationValue.Set && this.value != value) {
            throw QuerySchemaConflictException("Conflicting query schema field leaf: [$leaf].")
        }
        return DeclarationValue.Set(value)
    }

    private companion object {
        val DECLARABLE_KINDS = setOf(QueryValueKind.SCALAR, QueryValueKind.OBJECT, QueryValueKind.ARRAY)
    }
}

fun querySchemaRegistration(
    aggregateType: KClass<*>,
    model: QueryModel,
    block: QuerySchemaDeclarationBuilder.() -> Unit,
): QuerySchemaRegistration = QuerySchemaRegistration(
    context = QuerySchemaContext(aggregateType.java.requiredNamedAggregate().materialize(), model),
    declaration = QuerySchemaDeclarationBuilder().apply(block).build(),
)
