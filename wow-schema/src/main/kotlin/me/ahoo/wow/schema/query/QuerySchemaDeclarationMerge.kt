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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import tools.jackson.databind.JsonNode
import java.util.concurrent.TimeUnit

internal fun <T> DeclarationValue<T>.or(default: T): T = (this as? DeclarationValue.Set)?.value ?: default

internal fun QueryFieldDeclaration.intersectDeclaration(other: QueryFieldDeclaration, field: QueryField): QueryFieldDeclaration {
    val leftKind = kind.or(QueryValueKind.UNKNOWN)
    val rightKind = other.kind.or(QueryValueKind.UNKNOWN)
    if (disjointNonNullShape(other)) {
        if (!allowsNull() || !other.allowsNull()) {
            throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.kind].")
        }
        if (hasMasks() || other.hasMasks()) {
            throw QuerySchemaConflictException(
                "Null-only intersection loses query schema protection: [$field.maskRule]."
            )
        }
        return copy(
            kind = DeclarationValue.Set(QueryValueKind.NULL),
            enumValues = enumValues.intersectEnums(other.enumValues, field, nullOnly = true),
            valueTypes = DeclarationValue.Set(emptySet()),
            nullable = DeclarationValue.Set(true),
            required = combineBoolean(required, other.required, Boolean::or),
            properties = DeclarationValue.Unset,
            items = DeclarationValue.Unset,
            additionalProperties = DeclarationValue.Unset,
            alternatives = DeclarationValue.Unset,
            semanticType = semanticType.requireSame(other.semanticType, field, "semanticType"),
        )
    }
    if (leftKind == QueryValueKind.UNION) {
        return alternatives.or(emptyList()).filterNot { it.disjointShape(other) }
            .map { it.intersectDeclaration(other, field) }.unionDeclaration(field)
    }
    if (rightKind == QueryValueKind.UNION) {
        return other.alternatives.or(emptyList()).filterNot { disjointShape(it) }
            .map { intersectDeclaration(it, field) }.unionDeclaration(field)
    }
    val shape = if (leftKind == QueryValueKind.UNKNOWN) other else this
    val children = properties.or(emptyMap()).toMutableMap()
    other.properties.or(emptyMap()).forEach { (name, child) ->
        children[name] = children[name]?.intersectDeclaration(child, QueryField("${field.path}.$name")) ?: child
    }
    return shape.copy(
        enumValues = enumValues.intersectEnums(other.enumValues, field),
        valueTypes = valueTypes.intersect(other.valueTypes, field),
        nullable = combineBoolean(nullable, other.nullable, Boolean::and),
        required = combineBoolean(required, other.required, Boolean::or),
        properties = if (children.isNotEmpty()) DeclarationValue.Set(children) else shape.properties,
        items = intersectChild(items, other.items, field),
        additionalProperties = intersectChild(additionalProperties, other.additionalProperties, field),
        semanticType = semanticType.requireSame(other.semanticType, field, "semanticType"),
        maskRule = maskRule.requireSame(other.maskRule, field, "maskRule"),
    )
}

private fun QueryFieldDeclaration.disjointShape(other: QueryFieldDeclaration): Boolean {
    if (kind.or(QueryValueKind.UNKNOWN) == QueryValueKind.UNION) {
        return alternatives.or(emptyList()).all {
            it.disjointShape(other)
        }
    }
    if (other.kind.or(QueryValueKind.UNKNOWN) == QueryValueKind.UNION) {
        return other.alternatives.or(emptyList()).all {
            disjointShape(it)
        }
    }
    val leftEnums = enumValues.or(null)
    val rightEnums = other.enumValues.or(null)
    if (leftEnums != null && rightEnums != null && leftEnums.none { it in rightEnums }) return true
    return !(allowsNull() && other.allowsNull()) && disjointNonNullShape(other)
}

private fun QueryFieldDeclaration.allowsNull(): Boolean = nullable.or(true) &&
    enumValues.or(null)?.none(JsonNode::isNull) != true &&
    (kind.or(QueryValueKind.UNKNOWN) != QueryValueKind.UNION || alternatives.or(emptyList()).any { it.allowsNull() })

private fun QueryFieldDeclaration.hasMasks(): Boolean = maskRule is DeclarationValue.Set ||
    properties.or(emptyMap()).values.any { it.hasMasks() } || items.or(null)?.hasMasks() == true ||
    additionalProperties.or(null)?.hasMasks() == true || alternatives.or(emptyList()).any { it.hasMasks() }

private fun QueryFieldDeclaration.disjointNonNullShape(other: QueryFieldDeclaration): Boolean {
    val left = kind.or(QueryValueKind.UNKNOWN)
    val right = other.kind.or(QueryValueKind.UNKNOWN)
    if (left == QueryValueKind.UNION) return alternatives.or(emptyList()).all { it.disjointNonNullShape(other) }
    if (right == QueryValueKind.UNION) return other.alternatives.or(emptyList()).all { disjointNonNullShape(it) }
    if (left == QueryValueKind.NULL || right == QueryValueKind.NULL) return true
    if (left == QueryValueKind.UNKNOWN || right == QueryValueKind.UNKNOWN) return false
    if (left != right) return true
    if (left != QueryValueKind.SCALAR) return false
    val first = valueTypes.or(emptySet())
    val second = other.valueTypes.or(emptySet())
    return first.intersect(second).isEmpty() &&
        !(QueryValueType.INTEGER in first && QueryValueType.DECIMAL in second || QueryValueType.DECIMAL in first && QueryValueType.INTEGER in second)
}

internal fun List<QueryFieldDeclaration>.unionDeclaration(field: QueryField): QueryFieldDeclaration {
    if (size == 1) return single()
    if (isEmpty()) throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.kind].")
    val rules = mutableMapOf<List<String>, MaskRule>()
    fun collect(value: QueryFieldDeclaration, path: List<String>) {
        value.maskRule.or(null)?.let { rule ->
            if (rules.putIfAbsent(path, rule)?.let { it != rule } == true) {
                throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.maskRule].")
            }
        }
        value.properties.or(emptyMap()).forEach { (name, child) -> collect(child, path + name) }
        value.items.or(null)?.let { collect(it, path + "[]") }
        value.additionalProperties.or(null)?.let { collect(it, path + "{}") }
        value.alternatives.or(emptyList()).forEach { collect(it, path) }
    }
    forEach { collect(it, emptyList()) }
    return QueryFieldDeclaration(
        kind = DeclarationValue.Set(QueryValueKind.UNION),
        alternatives = DeclarationValue.Set(this),
        nullable = DeclarationValue.Set(any { it.nullable.or(true) }),
        required = DeclarationValue.Set(all { it.required.or(false) }),
    )
}

internal fun QueryFieldDeclaration.isStringDomain(): Boolean = when (kind.or(QueryValueKind.UNKNOWN)) {
    QueryValueKind.SCALAR -> valueTypes.or(emptySet()) == setOf(QueryValueType.STRING)
    QueryValueKind.ARRAY -> items.or(null)?.isStringDomain() == true
    QueryValueKind.UNION -> alternatives.or(emptyList()).all {
        it.kind.or(QueryValueKind.UNKNOWN) == QueryValueKind.NULL || it.isStringDomain()
    }
    else -> false
}

internal fun QueryFieldDeclaration.withEpoch(unit: TimeUnit): QueryFieldDeclaration = when (
    kind.or(
        QueryValueKind.UNKNOWN
    )
) {
    QueryValueKind.ARRAY -> copy(items = DeclarationValue.Set(checkNotNull(items.or(null)).withEpoch(unit)))
    QueryValueKind.UNION -> copy(
        alternatives = DeclarationValue.Set(
            alternatives.or(emptyList()).map {
                if (it.kind.or(QueryValueKind.UNKNOWN) == QueryValueKind.NULL) it else it.withEpoch(unit)
            }
        )
    )
    QueryValueKind.SCALAR -> {
        if (valueTypes.or(emptySet()) != setOf(QueryValueType.INTEGER)) {
            throw QuerySchemaConflictException("@QueryTemporal requires an integer JSON wire shape.")
        }
        val epoch = Temporal.Epoch(unit)
        val previous = semanticType.or(null)
        if (previous != null && previous != epoch) {
            throw QuerySchemaConflictException(
                "Conflicting query schema temporal unit."
            )
        }
        copy(semanticType = DeclarationValue.Set(epoch))
    }
    else -> throw QuerySchemaConflictException("@QueryTemporal requires an integer JSON wire shape.")
}

private fun intersectChild(
    left: DeclarationValue<QueryFieldDeclaration?>,
    right: DeclarationValue<QueryFieldDeclaration?>,
    field: QueryField,
): DeclarationValue<QueryFieldDeclaration?> {
    if (left === DeclarationValue.Unset) return right
    if (right === DeclarationValue.Unset) return left
    val first = left.or(null)
    val second = right.or(null)
    if (first == null || second == null) return DeclarationValue.Set(null)
    return DeclarationValue.Set(first.intersectDeclaration(second, field))
}

private fun combineBoolean(
    left: DeclarationValue<Boolean>,
    right: DeclarationValue<Boolean>,
    operation: (Boolean, Boolean) -> Boolean,
): DeclarationValue<Boolean> = when {
    left !is DeclarationValue.Set -> right
    right !is DeclarationValue.Set -> left
    else -> DeclarationValue.Set(operation(left.value, right.value))
}

private fun DeclarationValue<Set<QueryValueType>>.intersect(
    other: DeclarationValue<Set<QueryValueType>>,
    field: QueryField,
): DeclarationValue<Set<QueryValueType>> = when {
    this !is DeclarationValue.Set -> other
    other !is DeclarationValue.Set -> this
    value.isEmpty() -> other
    other.value.isEmpty() -> this
    else -> {
        val intersection = value.intersect(other.value).toMutableSet()
        val integerToDecimal = QueryValueType.INTEGER in value && QueryValueType.DECIMAL in other.value
        val decimalToInteger = QueryValueType.DECIMAL in value && QueryValueType.INTEGER in other.value
        if (integerToDecimal || decimalToInteger) {
            intersection += QueryValueType.INTEGER
        }
        DeclarationValue.Set(
            intersection.takeIf { it.isNotEmpty() }
                ?: throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.valueTypes].")
        )
    }
}

private fun DeclarationValue<List<JsonNode>?>.intersectEnums(
    other: DeclarationValue<List<JsonNode>?>,
    field: QueryField,
    nullOnly: Boolean = false,
): DeclarationValue<List<JsonNode>?> {
    val left = or(null)
    val right = other.or(null)
    val result = when {
        left == null -> right
        right == null -> left
        else -> left.intersect(right.toSet()).toList()
    }?.let { values -> if (nullOnly) values.filter(JsonNode::isNull) else values }
    if (result?.isEmpty() == true) {
        throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.enumValues].")
    }
    return if (result == null) this else DeclarationValue.Set(result)
}

private fun <T> DeclarationValue<T>.requireSame(other: DeclarationValue<T>, field: QueryField, leaf: String): DeclarationValue<T> {
    if (this === DeclarationValue.Unset) return other
    if (other === DeclarationValue.Unset || this == other) return this
    throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
}
