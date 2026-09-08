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
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.REQUIRED
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.VALUE_TYPES
import tools.jackson.databind.JsonNode
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

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

class QueryFieldDeclarationBuilder {
    private var title: DeclarationValue<String?> = DeclarationValue.Unset
    private var description: DeclarationValue<String?> = DeclarationValue.Unset
    private var enumValues: DeclarationValue<List<JsonNode>?> = DeclarationValue.Unset
    private var valueTypes: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset
    private var nullable: DeclarationValue<Boolean> = DeclarationValue.Unset
    private var required: DeclarationValue<Boolean> = DeclarationValue.Unset
    private var kind: DeclarationValue<QueryValueKind> = DeclarationValue.Unset
    private var properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = DeclarationValue.Unset
    private var items: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset
    private var additionalProperties: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset
    private var alternatives: DeclarationValue<List<QueryFieldDeclaration>> = DeclarationValue.Unset
    private var semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset

    fun title(value: String?) {
        title = title.set(value, TITLE)
    }

    fun description(value: String?) {
        description = description.set(value, DESCRIPTION)
    }

    fun enumValues(value: List<JsonNode>?) {
        enumValues = enumValues.set(value, ENUM_VALUES)
    }

    fun valueTypes(vararg value: QueryValueType) {
        valueTypes = valueTypes.set(value.toSet(), VALUE_TYPES)
    }

    fun nullable(value: Boolean) {
        nullable = nullable.set(value, NULLABLE)
    }

    fun required(value: Boolean) {
        required = required.set(value, REQUIRED)
    }

    fun kind(value: QueryValueKind) {
        kind = kind.set(value, "kind")
    }

    fun property(name: String, block: QueryFieldDeclarationBuilder.() -> Unit) {
        requireQueryPathSegment(name)
        val declaration = QueryFieldDeclarationBuilder().apply(block).build()
        val children = properties.valueOr(emptyMap()).toMutableMap()
        children[name] = children[name]?.merge(declaration, QueryField(name), true) ?: declaration
        properties = DeclarationValue.Set(children)
    }

    fun items(block: QueryFieldDeclarationBuilder.() -> Unit) {
        items(QueryFieldDeclarationBuilder().apply(block).build())
    }

    fun items(value: QueryFieldDeclaration?) {
        items = items.set(value, "items")
    }

    fun additionalProperties(block: QueryFieldDeclarationBuilder.() -> Unit) {
        additionalProperties(QueryFieldDeclarationBuilder().apply(block).build())
    }

    fun additionalProperties(value: QueryFieldDeclaration?) {
        additionalProperties = additionalProperties.set(value, "additionalProperties")
    }

    fun alternative(block: QueryFieldDeclarationBuilder.() -> Unit) {
        alternatives = DeclarationValue.Set(alternatives.valueOr(emptyList()) + QueryFieldDeclarationBuilder().apply(block).build())
    }

    fun alternatives(value: List<QueryFieldDeclaration>) {
        alternatives = alternatives.set(value, "alternatives")
    }

    fun semanticType(value: QuerySemanticType?) {
        semanticType = semanticType.set(value, SEMANTIC_TYPE)
    }

    fun temporalEpoch(unit: TimeUnit = TimeUnit.MILLISECONDS) {
        semanticType(Temporal.Epoch(unit))
    }

    fun build(): QueryFieldDeclaration = QueryFieldDeclaration(
        title = title,
        description = description,
        enumValues = enumValues,
        valueTypes = valueTypes,
        nullable = nullable,
        required = required,
        kind = kind,
        properties = properties,
        items = items,
        additionalProperties = additionalProperties,
        alternatives = alternatives,
        semanticType = semanticType,
    )

    private fun <T> DeclarationValue<T>.set(value: T, leaf: String): DeclarationValue<T> {
        if (this is DeclarationValue.Set && this.value != value) {
            throw QuerySchemaConflictException("Conflicting query schema field leaf: [$leaf].")
        }
        return DeclarationValue.Set(value)
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
