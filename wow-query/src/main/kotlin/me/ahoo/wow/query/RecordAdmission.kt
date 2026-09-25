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
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.NotExistsFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SpaceIdFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.serialization.state.StateAggregateRecords
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/*
 * Admission of one record read without a backend query, such as a state point read: the caller's filters are
 * evaluated in memory and the response masks applied, so the read obeys the same scope, policies and masks as a query.
 */

/** Masks [record], a record of this schema's model, by the schema's response masks, in place. */
fun QueryModelSchema.maskRecord(record: ObjectNode): ObjectNode = SchemaMasker.create(this)?.mask(record) ?: record

/**
 * Whether [record], a snapshot record, satisfies this filter, evaluated in memory.
 *
 * Supported: `MATCH_ALL`, `MATCH_NONE`, `AND`, `OR`, `NOR`, the id, tenant, owner, space and deletion filters, and on a
 * field `EXISTS`, `NOT_EXISTS`, `IS_EMPTY`, `EQ` and `IN`. These are what caller scopes and ABAC policies produce.
 * A field path that crosses an array matches when any element does. A field exists when it is present and not
 * `null`. Any other node is unsupported and does not match, so an unforeseen restriction fails closed.
 */
fun FilterExpression.admitsRecord(record: ObjectNode): Boolean = when (this) {
    MatchAllFilter -> true
    MatchNoneFilter -> false
    is AndFilter -> operands.all { it.admitsRecord(record) }
    is OrFilter -> operands.any { it.admitsRecord(record) }
    is NorFilter -> operands.none { it.admitsRecord(record) }
    else -> admitsByMetadata(record) ?: admitsByField(record) ?: false
}

/** The id, tenant, owner, space and deletion filters; `null` for any other node. */
private fun FilterExpression.admitsByMetadata(record: ObjectNode): Boolean? {
    val id = record.text(MessageRecords.AGGREGATE_ID)
    return when (this) {
        is IdFilter -> id == value
        is IdsFilter -> id in values
        is AggregateIdFilter -> id == value
        is AggregateIdsFilter -> id in values
        is TenantIdFilter -> record.text(MessageRecords.TENANT_ID) == value
        is OwnerIdFilter -> record.text(MessageRecords.OWNER_ID) == value
        is SpaceIdFilter -> record.text(MessageRecords.SPACE_ID) == value
        is DeletionFilter -> deletionState.admits(record.get(StateAggregateRecords.DELETED)?.booleanValue() == true)
        else -> null
    }
}

private fun DeletionState.admits(deleted: Boolean): Boolean = when (this) {
    DeletionState.ACTIVE -> !deleted
    DeletionState.DELETED -> deleted
    DeletionState.ALL -> true
}

/** The field nodes; `null` for any other node. */
private fun FilterExpression.admitsByField(record: ObjectNode): Boolean? = when (this) {
    is ExistsFilter -> record.values(field).any { !it.isNull }
    is NotExistsFilter -> record.values(field).none { !it.isNull }
    is IsEmptyFilter -> record.values(field).any { it.isArray && it.isEmpty }
    is EqualFilter -> record.leaves(field).any { it == value }
    is InFilter -> record.leaves(field).any { it in values }
    else -> null
}

private fun ObjectNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()

/** The nodes at [field]'s path; an array on the way contributes each element. */
private fun ObjectNode.values(field: QueryField): List<JsonNode> =
    field.path.split('.').fold(listOf<JsonNode>(this)) { nodes, name ->
        nodes.flatMap { node ->
            val children = if (node.isArray) node.toList() else listOf(node)
            children.mapNotNull { it.takeIf(JsonNode::isObject)?.get(name) }
        }
    }

/** The scalar values at [field]'s path, with a terminal array contributing each element. */
private fun ObjectNode.leaves(field: QueryField): List<JsonNode> =
    values(field).flatMap { if (it.isArray) it.toList() else listOf(it) }
