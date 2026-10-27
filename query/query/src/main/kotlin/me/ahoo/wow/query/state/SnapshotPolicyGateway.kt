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

import kotlinx.coroutines.flow.Flow
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModelSchemaProvider
import tools.jackson.databind.node.ObjectNode

/**
 * The snapshot query policies of an aggregate type no backend serves the snapshot queries of
 * (`@AggregateRoot(snapshot = false)`, snapshots in memory without `wow.query.memory.enabled`, an application's own
 * stores): [admitRecord] admits a state as a [me.ahoo.wow.query.DefaultQueryGateway] of its snapshots would, under
 * [policies] (the caller scope, ABAC, the application's) against [schemaProvider]'s snapshot schema
 * ([me.ahoo.wow.query.schema.QuerySchemaCatalog.policyProvider]), so [StateVisibility] hides from a state read and the
 * events model what those policies would hide from a snapshot query. It answers no query: every query fails, as its
 * snapshots are not queried, and no route serves it.
 */
@InternalWowApi
class SnapshotPolicyGateway(
    override val aggregateType: AggregateType,
    private val schemaProvider: QueryModelSchemaProvider,
    policies: List<QueryPolicy>,
    override val entryPolicy: QueryEntryPolicy = QueryEntryPolicy.DEFAULT,
) : QueryGateway {
    private val admission = QueryAdmission(aggregateType, policies = policies, entryPolicy = entryPolicy)

    override suspend fun admitRecord(selection: FilterExpression, record: ObjectNode, caller: QueryCaller): ObjectNode? =
        admission.admitRecord(selection, record, schemaProvider.schema(), caller)

    override suspend fun single(query: ISingleQuery, caller: QueryCaller): ObjectNode? = throw unqueried()

    override fun list(query: IListQuery, caller: QueryCaller): Flow<ObjectNode> = throw unqueried()

    override suspend fun paged(query: IPagedQuery, caller: QueryCaller): PagedList<ObjectNode> = throw unqueried()

    override suspend fun cursor(query: ICursorQuery, caller: QueryCaller): CursorPage<ObjectNode> = throw unqueried()

    override suspend fun count(query: ICountQuery, caller: QueryCaller): CountResult = throw unqueried()

    override fun aggregate(query: AggregationQuery, caller: QueryCaller): Flow<ObjectNode> = throw unqueried()

    override suspend fun describe(entry: QueryEntry, defaultListSize: Int?): QueryModelDescriptor = throw unqueried()

    /** The failure of every query: its snapshots are not queried. */
    private fun unqueried(): QueryExecutionException =
        QueryExecutionException("The snapshots of $aggregateType are not queried.")
}
