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

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.CARDINALITY
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DYNAMIC_CHILDREN
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.REQUIRED
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.VALUE_TYPES
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode

object QuerySchemaDeclarationProperties {
    const val FIELDS = "fields"
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val ENUM_VALUES = "enumValues"
    const val VALUE_TYPES = "valueTypes"
    const val NULLABLE = "nullable"
    const val REQUIRED = "required"
    const val CARDINALITY = "cardinality"
    const val SEMANTIC_TYPE = "semanticType"
    const val DYNAMIC_CHILDREN = "dynamicChildren"
}

sealed interface DeclarationValue<out T> {
    data object Unset : DeclarationValue<Nothing>

    data class Set<T>(val value: T) : DeclarationValue<T>
}

data class QuerySchemaContext(
    val namedAggregate: NamedAggregate,
    val model: QueryModel,
)

data class QuerySchemaRegistration(
    val context: QuerySchemaContext,
    val declaration: QuerySchemaDeclaration,
)

data class QuerySchemaDeclaration(
    val fields: Map<LogicalField, QueryFieldDeclaration>,
)

data class QueryFieldDeclaration(
    val title: DeclarationValue<String?> = DeclarationValue.Unset,
    val description: DeclarationValue<String?> = DeclarationValue.Unset,
    val enumValues: DeclarationValue<List<JsonNode>?> = DeclarationValue.Unset,
    val valueTypes: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset,
    val nullable: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val required: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val cardinality: DeclarationValue<QueryCardinality> = DeclarationValue.Unset,
    val semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    val dynamicChildren: DeclarationValue<Boolean> = DeclarationValue.Unset,
    @get:JsonIgnore val maskRule: DeclarationValue<MaskRule> = DeclarationValue.Unset,
)

interface QuerySchemaSource {
    val priority: Int

    fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration>

    fun refresh(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = load(context)
}

object QuerySchemaSourcePriority {
    const val JSON_SCHEMA = 100
    const val CLASSPATH = 200
    const val BEAN = 300
    const val WORKING_DIRECTORY = 400
}

internal data class PrioritizedQuerySchemaDeclaration(
    val priority: Int,
    val declaration: QuerySchemaDeclaration,
)

internal fun QueryFieldDeclaration.merge(
    higher: QueryFieldDeclaration,
    field: LogicalField,
    rejectDifferent: Boolean,
): QueryFieldDeclaration = QueryFieldDeclaration(
    title = title.merge(higher.title, field, TITLE, rejectDifferent),
    description = description.merge(higher.description, field, DESCRIPTION, rejectDifferent),
    enumValues = enumValues.merge(higher.enumValues, field, ENUM_VALUES, rejectDifferent),
    valueTypes = valueTypes.merge(higher.valueTypes, field, VALUE_TYPES, rejectDifferent),
    nullable = nullable.merge(higher.nullable, field, NULLABLE, rejectDifferent),
    required = required.merge(higher.required, field, REQUIRED, rejectDifferent),
    cardinality = cardinality.merge(higher.cardinality, field, CARDINALITY, rejectDifferent),
    semanticType = semanticType.merge(higher.semanticType, field, SEMANTIC_TYPE, rejectDifferent),
    dynamicChildren = dynamicChildren.merge(higher.dynamicChildren, field, DYNAMIC_CHILDREN, rejectDifferent),
    maskRule = maskRule.mergeMaskRule(higher.maskRule, field),
)

private fun DeclarationValue<MaskRule>.mergeMaskRule(
    higher: DeclarationValue<MaskRule>,
    field: LogicalField,
): DeclarationValue<MaskRule> {
    if (this is DeclarationValue.Unset) return higher
    if (higher is DeclarationValue.Unset) return this
    if ((this as DeclarationValue.Set).value == (higher as DeclarationValue.Set).value) return this
    throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.maskRule].")
}

private fun <T> DeclarationValue<T>.merge(
    higher: DeclarationValue<T>,
    field: LogicalField,
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
