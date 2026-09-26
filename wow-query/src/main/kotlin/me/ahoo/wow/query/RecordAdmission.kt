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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AggregateIdsFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ExistsFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.IdsFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotEqualFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.NotInFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.api.query.spec.spec
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.POJONode
import java.math.BigDecimal

/** Masks [record], a record of this schema's model, by the schema's response masks, in place. */
fun QueryModelSchema.maskRecord(record: ObjectNode): ObjectNode = SchemaMasker.create(this)?.mask(record) ?: record

/**
 * The in-memory evaluation of a point read's restriction on one snapshot-shaped record ([QueryAdmission.admitRecord]).
 * [filter] is already canonical and normalized: aliases replaced, `EQ`/`NE` of `null` lowered to `IS_NULL`/
 * `IS_NOT_NULL`, `IS_EMPTY_STRING` lowered to `EQ ""`.
 *
 * The semantics are those of the filter semantics matrix ([me.ahoo.wow.api.query.spec.FilterSemantics]), which every
 * backend reproduces: numbers compare by value whatever their JSON width, a scalar operand matches any element of an
 * array field and an array operand of `EQ` matches the whole array, negations match a record without the field, and
 * a field set to `null` exists but is null.
 *
 * Supported: `MATCH_ALL`, `MATCH_NONE`, `AND`, `OR`, `NOR`, the id, tenant, owner, space and deletion filters, and on a
 * field `EQ`, `NE`, `IN`, `NOT_IN`, `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS` and `IS_EMPTY`. These are what
 * caller scopes and ABAC policies produce. Any other node is unsupported and matches nothing, so an unforeseen
 * restriction fails closed.
 */
internal class RecordFilter(private val filter: FilterExpression) {
    fun admits(record: ObjectNode): Boolean = filter.admits(record)

    private fun FilterExpression.admits(record: ObjectNode): Boolean = when (this) {
        MatchAllFilter -> true
        MatchNoneFilter -> false
        is AndFilter -> operands.all { it.admits(record) }
        is OrFilter -> operands.any { it.admits(record) }
        is NorFilter -> operands.none { it.admits(record) }
        else -> admitsByMetadata(record) ?: admitsByField(record) ?: false
    }

    /** The id, tenant, owner, space and deletion filters; `null` for any other node. */
    private fun FilterExpression.admitsByMetadata(record: ObjectNode): Boolean? {
        fun text(): String? = record.text(SnapshotQueryModelProfile.systemField(checkNotNull(spec.systemField)).path)
        return when (this) {
            is IdFilter -> text() == value
            is IdsFilter -> text() in values
            is AggregateIdFilter -> text() == value
            is AggregateIdsFilter -> text() in values
            is TenantIdFilter -> text() == value
            is OwnerIdFilter -> text() == value
            is SpaceIdFilter -> text() == value
            is DeletionFilter -> deletionState.admits(
                record.get(SnapshotQueryModelProfile.systemField(SystemField.DELETED).path)?.booleanValue() == true
            )
            else -> null
        }
    }

    private fun DeletionState.admits(deleted: Boolean): Boolean = when (this) {
        DeletionState.ACTIVE -> !deleted
        DeletionState.DELETED -> deleted
        DeletionState.ALL -> true
    }

    /** The field nodes; `null` for any other node. */
    private fun FilterExpression.admitsByField(record: ObjectNode): Boolean? =
        admitsByValue(record) ?: admitsByPresence(record)

    /** The comparisons of a field's values with operands. */
    private fun FilterExpression.admitsByValue(record: ObjectNode): Boolean? = when (this) {
        is EqualFilter -> record.values(field).any { it.matches(value.canonical()) }
        is NotEqualFilter -> record.values(field).none { it.matches(value.canonical()) }
        is InFilter -> values.map { it.canonical() }
            .let { operands -> record.values(field).any { it.matchesAny(operands) } }
        is NotInFilter -> values.map { it.canonical() }
            .let { operands -> record.values(field).none { it.matchesAny(operands) } }
        else -> null
    }

    /** Whether a field is present, null or an empty array. */
    private fun FilterExpression.admitsByPresence(record: ObjectNode): Boolean? = when (this) {
        is IsNullFilter -> record.values(field).let { values -> values.isEmpty() || values.any { it.isNull } }
        is IsNotNullFilter -> record.values(field).let { values -> values.isNotEmpty() && values.none { it.isNull } }
        is ExistsFilter -> record.values(field).isNotEmpty()
        is NotExistsFilter -> record.values(field).isEmpty()
        is IsEmptyFilter -> record.values(field).any { it.isArray && it.isEmpty }
        else -> null
    }

    private fun JsonNode.matchesAny(operands: List<JsonNode>): Boolean = operands.any { matches(it) }

    /** Whether this stored value matches [operand]: equal by value, or, for an array, holding an element that is. */
    private fun JsonNode.matches(operand: JsonNode): Boolean =
        sameValue(operand) || (isArray && !operand.isArray && any { it.sameValue(operand) })

    private fun JsonNode.sameValue(other: JsonNode): Boolean = when {
        isNumber && other.isNumber -> decimalOrNull()?.let { left ->
            other.decimalOrNull()?.let { left.compareTo(it) == 0 }
        } ?: (doubleValue() == other.doubleValue())
        isArray && other.isArray -> size() == other.size() && (0 until size()).all { get(it).sameValue(other.get(it)) }
        else -> this == other
    }

    /** The exact value of a number; `null` for a non-finite floating-point value. */
    private fun JsonNode.decimalOrNull(): BigDecimal? =
        if (isFloatingPointNumber && !doubleValue().isFinite()) null else decimalValue()

    /** A value a caller wrapped as a Java object (an enum, a value class), as the JSON it serializes to. */
    private fun JsonNode.canonical(): JsonNode = if (this is POJONode) JsonSerializer.valueToTree(pojo) else this

    private fun ObjectNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()

    /**
     * The values stored at [field]'s path; absent segments contribute nothing, so an empty list means the field is
     * missing. An array on the way contributes each element's child, and a numeric segment on an array its element
     * at that index. [QueryField]'s grammar keeps `.` out of a segment, so the segments are exactly the path's.
     */
    private fun ObjectNode.values(field: QueryField): List<JsonNode> =
        field.path.split('.').fold(listOf<JsonNode>(this)) { nodes, name -> nodes.flatMap { it.child(name) } }

    private fun JsonNode.child(name: String): List<JsonNode> = when {
        isObject -> listOfNotNull(get(name))
        isArray -> name.toIntOrNull()?.let { listOfNotNull(get(it)) } ?: filter { it.isObject }.mapNotNull { it.get(name) }
        else -> emptyList()
    }
}
