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

package me.ahoo.wow.schema.query

import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.CARDINALITY
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DYNAMIC_CHILDREN
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE

private const val MASK_RULE = "maskRule"

private fun QueryFieldDeclaration.mergeStructural(
    other: QueryFieldDeclaration,
    field: LogicalField,
    valueTypes: DeclarationValue<Set<QueryValueType>>,
    required: Boolean,
): QueryFieldDeclaration = QueryFieldDeclaration(
    title = title.requireSame(other.title, field, TITLE),
    description = description.requireSame(other.description, field, DESCRIPTION),
    enumValues = enumValues.requireSame(other.enumValues, field, ENUM_VALUES),
    valueTypes = valueTypes,
    nullable = nullable.requireSame(other.nullable, field, NULLABLE),
    required = DeclarationValue.Set(required),
    cardinality = cardinality.requireSame(other.cardinality, field, CARDINALITY),
    semanticType = semanticType.requireSame(other.semanticType, field, SEMANTIC_TYPE),
    dynamicChildren = dynamicChildren.requireSame(other.dynamicChildren, field, DYNAMIC_CHILDREN),
    maskRule = maskRule.requireSame(other.maskRule, field, MASK_RULE),
)

internal fun MutableMap<LogicalField, QueryFieldDeclaration>.mergeConjunctive(
    other: Map<LogicalField, QueryFieldDeclaration>,
) {
    other.forEach { (field, next) ->
        merge(field, next) { current, merged ->
            current.mergeStructural(
                merged,
                field,
                current.valueTypes.intersect(merged.valueTypes, field),
                current.isRequired() || merged.isRequired(),
            )
        }
    }
    requireMaskedStringFields()
}

internal fun List<Map<LogicalField, QueryFieldDeclaration>>.mergeAlternatives():
    Map<LogicalField, QueryFieldDeclaration> {
    val merged = linkedMapOf<LogicalField, QueryFieldDeclaration>()
    forEach { alternative ->
        alternative.forEach { (field, next) ->
            merged.merge(field, next) { current, branch ->
                current.mergeStructural(
                    branch,
                    field,
                    current.valueTypes.union(branch.valueTypes),
                    current.isRequired() && branch.isRequired(),
                )
            }
        }
    }
    return merged.mapValuesTo(linkedMapOf()) { (field, declaration) ->
        declaration.copy(
            required = DeclarationValue.Set(all { alternative -> alternative[field]?.isRequired() == true }),
        )
    }.also(Map<LogicalField, QueryFieldDeclaration>::requireMaskedStringFields)
}

private fun Map<LogicalField, QueryFieldDeclaration>.requireMaskedStringFields() {
    forEach { (_, declaration) ->
        if (declaration.maskRule is DeclarationValue.Set &&
            declaration.valueTypes != DeclarationValue.Set(setOf(QueryValueType.STRING))
        ) {
            throw QuerySchemaConflictException("Masked query schema field must have STRING value type.")
        }
    }
}

private fun QueryFieldDeclaration.isRequired(): Boolean =
    (required as? DeclarationValue.Set)?.value == true

private fun DeclarationValue<Set<QueryValueType>>.union(
    other: DeclarationValue<Set<QueryValueType>>,
): DeclarationValue<Set<QueryValueType>> = when {
    this is DeclarationValue.Set && other is DeclarationValue.Set -> DeclarationValue.Set(value + other.value)
    this is DeclarationValue.Set -> this
    else -> other
}

private fun DeclarationValue<Set<QueryValueType>>.intersect(
    other: DeclarationValue<Set<QueryValueType>>,
    field: LogicalField,
): DeclarationValue<Set<QueryValueType>> = when {
    this !is DeclarationValue.Set -> other
    other !is DeclarationValue.Set -> this
    value.isEmpty() -> other
    other.value.isEmpty() -> this
    else -> {
        val intersection = value.intersect(other.value).toMutableSet()
        val integerOnLeft = QueryValueType.INTEGER in value && QueryValueType.DECIMAL in other.value
        val integerOnRight = QueryValueType.DECIMAL in value && QueryValueType.INTEGER in other.value
        if (integerOnLeft || integerOnRight) {
            intersection += QueryValueType.INTEGER
        }
        DeclarationValue.Set(
            intersection.takeIf(Set<QueryValueType>::isNotEmpty)
                ?: throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.valueTypes]."),
        )
    }
}

private fun <T> DeclarationValue<T>.requireSame(
    other: DeclarationValue<T>,
    field: LogicalField,
    leaf: String,
): DeclarationValue<T> {
    if (this === DeclarationValue.Unset) return other
    if (other === DeclarationValue.Unset || this == other) return this
    throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
}
