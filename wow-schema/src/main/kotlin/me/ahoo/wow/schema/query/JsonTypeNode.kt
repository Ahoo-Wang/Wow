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

import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QueryMemberFact
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QueryTypeFact
import tools.jackson.databind.JsonNode

/**
 * The walker's working form of one JSON Schema value while compositions (`$ref`, `allOf`, `anyOf`, `oneOf`) are
 * resolved. `null` properties are facts the schema has not stated yet; [additionalProperties] distinguishes "not
 * stated" (`null`) from a closed object ([Slot] with no value).
 */
internal data class JsonTypeNode(
    val kind: QueryValueKind? = null,
    val valueTypes: Set<QueryValueType>? = null,
    val nullable: Boolean? = null,
    val required: Boolean? = null,
    val enumValues: List<JsonNode>? = null,
    val properties: Map<String, JsonTypeNode>? = null,
    val items: JsonTypeNode? = null,
    val additionalProperties: Slot? = null,
    val alternatives: List<JsonTypeNode>? = null,
    val formats: Set<String> = emptySet(),
    val members: List<QueryMemberFact> = emptyList(),
    val omitted: List<QueryMemberFact> = emptyList(),
    val title: String? = null,
    val description: String? = null,
) {
    /** An object's `additionalProperties`: the value of every other key, or `null` when the object is closed. */
    data class Slot(val value: JsonTypeNode?)

    val kindOrUnknown: QueryValueKind
        get() = kind ?: QueryValueKind.UNKNOWN

    fun toFact(): QueryTypeFact = QueryTypeFact(
        kind = kindOrUnknown,
        valueTypes = valueTypes.orEmpty(),
        nullable = nullable,
        required = required,
        enumValues = enumValues,
        title = title,
        description = description,
        formats = formats,
        properties = properties.orEmpty().mapValues { (_, child) -> child.toFact() },
        items = items?.toFact(),
        additionalProperties = additionalProperties?.value?.toFact(),
        alternatives = alternatives.orEmpty().map(JsonTypeNode::toFact),
        member = members.singleOrNull() ?: members.takeIf { it.isNotEmpty() }?.let(::mergeMembers),
        omitted = omitted,
    )

    /** Every member fact at or below this node. */
    fun descendantMembers(): List<QueryMemberFact> = members + omitted +
        properties.orEmpty().values.flatMap { it.descendantMembers() } + items?.descendantMembers().orEmpty() +
        additionalProperties?.value?.descendantMembers().orEmpty() +
        alternatives.orEmpty().flatMap { it.descendantMembers() }
}

/** One member reached through several composed nodes keeps every annotation any of them reported. */
private fun mergeMembers(members: List<QueryMemberFact>): QueryMemberFact =
    QueryMemberFact(members.first().name, members.first().type, members.flatMap { it.annotations }.distinct())

/** The schema's `allOf` / `$ref` meaning: a value must satisfy both nodes. */
internal fun JsonTypeNode.intersect(other: JsonTypeNode, field: String): JsonTypeNode {
    val leftKind = kindOrUnknown
    val rightKind = other.kindOrUnknown
    if (disjointNonNullShape(other)) {
        if (!allowsNull() || !other.allowsNull()) {
            throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.kind].")
        }
        // Only null survives: the value structure is gone, so the members it declared are reported as omitted.
        return copy(
            kind = QueryValueKind.NULL,
            enumValues = intersectEnums(other, field, nullOnly = true),
            valueTypes = emptySet(),
            nullable = true,
            required = combine(required, other.required, Boolean::or),
            properties = null,
            items = null,
            additionalProperties = null,
            alternatives = null,
            formats = formats + other.formats,
            members = (members + other.members).distinct(),
            omitted = (descendantMembers() + other.descendantMembers() - members.toSet() - other.members.toSet())
                .distinct(),
        )
    }
    if (leftKind == QueryValueKind.UNION) {
        return alternatives.orEmpty().filterNot { it.disjointShape(other) }
            .map { it.intersect(other, field) }.union(field)
    }
    if (rightKind == QueryValueKind.UNION) {
        return other.alternatives.orEmpty().filterNot { disjointShape(it) }
            .map { intersect(it, field) }.union(field)
    }
    val shape = if (leftKind == QueryValueKind.UNKNOWN) other else this
    val children = properties.orEmpty().toMutableMap()
    other.properties.orEmpty().forEach { (name, child) ->
        children[name] = children[name]?.intersect(child, "$field.$name") ?: child
    }
    return shape.copy(
        enumValues = intersectEnums(other, field),
        valueTypes = intersectValueTypes(valueTypes, other.valueTypes, field),
        nullable = combine(nullable, other.nullable, Boolean::and),
        required = combine(required, other.required, Boolean::or),
        properties = if (children.isNotEmpty()) children else shape.properties,
        items = intersectChild(items, other.items, field),
        additionalProperties = intersectSlot(additionalProperties, other.additionalProperties, field),
        formats = formats + other.formats,
        members = (members + other.members).distinct(),
        omitted = (omitted + other.omitted).distinct(),
    )
}

/** The schema's `anyOf` / `oneOf` meaning: a value satisfies at least one node. */
internal fun List<JsonTypeNode>.union(field: String): JsonTypeNode {
    if (size == 1) return single()
    if (isEmpty()) throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.kind].")
    return JsonTypeNode(
        kind = QueryValueKind.UNION,
        alternatives = this,
        nullable = any { it.nullable ?: true },
        required = all { it.required ?: false },
    )
}

private fun JsonTypeNode.disjointShape(other: JsonTypeNode): Boolean {
    if (kindOrUnknown == QueryValueKind.UNION) return alternatives.orEmpty().all { it.disjointShape(other) }
    if (other.kindOrUnknown == QueryValueKind.UNION) return other.alternatives.orEmpty().all { disjointShape(it) }
    val leftEnums = enumValues
    val rightEnums = other.enumValues
    if (leftEnums != null && rightEnums != null && leftEnums.none { it in rightEnums }) return true
    return !(allowsNull() && other.allowsNull()) && disjointNonNullShape(other)
}

private fun JsonTypeNode.allowsNull(): Boolean = (nullable ?: true) &&
    enumValues?.none(JsonNode::isNull) != true &&
    (kindOrUnknown != QueryValueKind.UNION || alternatives.orEmpty().any { it.allowsNull() })

private fun JsonTypeNode.disjointNonNullShape(other: JsonTypeNode): Boolean {
    val left = kindOrUnknown
    val right = other.kindOrUnknown
    if (left == QueryValueKind.UNION) return alternatives.orEmpty().all { it.disjointNonNullShape(other) }
    if (right == QueryValueKind.UNION) return other.alternatives.orEmpty().all { disjointNonNullShape(it) }
    if (left == QueryValueKind.NULL || right == QueryValueKind.NULL) return true
    if (left == QueryValueKind.UNKNOWN || right == QueryValueKind.UNKNOWN) return false
    if (left != right) return true
    if (left != QueryValueKind.SCALAR) return false
    val first = valueTypes.orEmpty()
    val second = other.valueTypes.orEmpty()
    return first.intersect(second).isEmpty() && !(
        QueryValueType.INTEGER in first && QueryValueType.DECIMAL in second ||
            QueryValueType.DECIMAL in first && QueryValueType.INTEGER in second
        )
}

private fun intersectChild(left: JsonTypeNode?, right: JsonTypeNode?, field: String): JsonTypeNode? = when {
    left == null -> right
    right == null -> left
    else -> left.intersect(right, field)
}

private fun intersectSlot(left: JsonTypeNode.Slot?, right: JsonTypeNode.Slot?, field: String): JsonTypeNode.Slot? {
    if (left == null) return right
    if (right == null) return left
    val first = left.value
    val second = right.value
    if (first == null || second == null) return JsonTypeNode.Slot(null)
    return JsonTypeNode.Slot(first.intersect(second, field))
}

private fun combine(left: Boolean?, right: Boolean?, operation: (Boolean, Boolean) -> Boolean): Boolean? = when {
    left == null -> right
    right == null -> left
    else -> operation(left, right)
}

private fun intersectValueTypes(
    left: Set<QueryValueType>?,
    right: Set<QueryValueType>?,
    field: String,
): Set<QueryValueType>? = when {
    left == null -> right
    right == null -> left
    left.isEmpty() -> right
    right.isEmpty() -> left
    else -> {
        val intersection = left.intersect(right).toMutableSet()
        val integerToDecimal = QueryValueType.INTEGER in left && QueryValueType.DECIMAL in right
        val decimalToInteger = QueryValueType.DECIMAL in left && QueryValueType.INTEGER in right
        if (integerToDecimal || decimalToInteger) {
            intersection += QueryValueType.INTEGER
        }
        intersection.takeIf { it.isNotEmpty() }
            ?: throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.valueTypes].")
    }
}

private fun JsonTypeNode.intersectEnums(other: JsonTypeNode, field: String, nullOnly: Boolean = false): List<JsonNode>? {
    val left = enumValues
    val right = other.enumValues
    val result = when {
        left == null -> right
        right == null -> left
        else -> left.intersect(right.toSet()).toList()
    }?.let { values -> if (nullOnly) values.filter(JsonNode::isNull) else values }
    if (result?.isEmpty() == true) {
        throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.enumValues].")
    }
    return result ?: enumValues
}
