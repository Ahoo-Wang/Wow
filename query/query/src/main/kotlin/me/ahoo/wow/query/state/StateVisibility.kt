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

package me.ahoo.wow.query.state

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.schema.SnapshotFields
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * One aggregate's committed state as the query policies read it: [ref]'s [state] at [version], of [scope], [deleted]
 * and [tags], the facts a snapshot record holds. Every entry that reads a state (a state read by id, a version or a
 * trace, the events model) hands it to [StateVisibility] in this form.
 */
@InternalWowApi
data class CommittedState(
    val ref: AggregateRef,
    val version: Int,
    val scope: Scope,
    val deleted: Boolean,
    val tags: Map<String, List<String>>,
    val state: JsonNode,
)

/**
 * Whether a caller sees one aggregate's state as the snapshot queries of its type show it (review A3-11): the state,
 * as the snapshot-shaped record a query reads, admitted by [QueryGateway.admitRecord] of the type's snapshot query
 * gateway, so the caller scope, ABAC (`wow.query.abac`) and the application's [me.ahoo.wow.query.QueryPolicy] beans
 * hide from a state read by id, a version, a trace and the events model ([me.ahoo.wow.query.events.EventVisibility])
 * the rows they hide from a query. A type without a snapshot query gateway has no policies to apply, and every state
 * of it is visible.
 *
 * What a state read returns is [admitted]: the state masked as the snapshot queries mask it (09 §17 G-2, the 9.x
 * `PointReadAdmission`), for every caller alike, as a query masks every row whoever asks.
 *
 * The record is read by id with every deletion state: a state read answers a deleted aggregate as any other.
 */
@InternalWowApi
object StateVisibility {
    /** Whether [caller] may see [state], whose tree may be masked in place ([admitted]). */
    suspend fun visible(gateway: QueryGateway?, caller: QueryCaller, state: CommittedState): Boolean =
        gateway == null || admitted(gateway, caller, state) != null

    /**
     * The tree of [state] as [caller] may see it: masked as the snapshot queries of its type mask it (09 §17 G-2), or
     * null when they hide it. With a [gateway], its admitted record's state, masked by the schema its queries are
     * masked by; without one, the tree masked by [masks], the masks the Manifest declares. The tree may be masked in
     * place.
     */
    suspend fun admitted(
        gateway: QueryGateway?,
        caller: QueryCaller,
        state: CommittedState,
        masks: PayloadMasks = PayloadMasks.NONE,
    ): JsonNode? {
        val ref = state.ref
        gateway ?: return masks.state(ref.type, state.state)
        val record = WowJson.mapper.createObjectNode().apply {
            put(SnapshotFields.TENANT_ID, ref.tenantId)
            put(SnapshotFields.AGGREGATE_ID, ref.id)
            put(SnapshotFields.VERSION, state.version)
            put(SnapshotFields.OWNER_ID, state.scope.ownerId)
            put(SnapshotFields.SPACE_ID, state.scope.spaceId)
            put(SnapshotFields.DELETED, state.deleted)
            set(SnapshotFields.TAGS, tagsOf(state.tags))
            set(SnapshotFields.STATE, state.state)
        }
        return gateway.admitRecord(selection(ref.id), record, caller)?.get(SnapshotFields.STATE)
    }

    /** A point read by [id], of every deletion state. */
    private fun selection(id: String) = AndFilter(listOf(IdFilter(id), DeletionFilter(DeletionState.ALL)))

    private fun tagsOf(tags: Map<String, List<String>>): ObjectNode = WowJson.mapper.createObjectNode().also { node ->
        tags.forEach { (name, values) -> node.putArray(name).also { array -> values.forEach(array::add) } }
    }
}
