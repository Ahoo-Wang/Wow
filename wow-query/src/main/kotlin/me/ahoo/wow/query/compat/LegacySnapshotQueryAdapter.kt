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

package me.ahoo.wow.query.compat

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DeletionScope
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.LegacyConditionExpression
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.MatchAll
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Query
import me.ahoo.wow.api.query.QueryErrorCode
import me.ahoo.wow.api.query.QueryException
import me.ahoo.wow.api.query.QueryExpression
import me.ahoo.wow.api.query.QueryProjection
import me.ahoo.wow.api.query.QueryScope
import me.ahoo.wow.api.query.QuerySort
import me.ahoo.wow.api.query.QuerySortDirection
import me.ahoo.wow.api.query.QueryStage
import me.ahoo.wow.api.query.SimpleDynamicDocument.Companion.toDynamicDocument
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.query.converter.DeleteConditionGuard.guard
import me.ahoo.wow.query.gateway.SnapshotQueryGateway
import me.ahoo.wow.query.gateway.SnapshotQueryGatewayFactory
import me.ahoo.wow.query.policy.QueryContexts
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.result.QueryMaterializer
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.context.Context
import tools.jackson.databind.node.ObjectNode

class GatewaySnapshotQueryServiceFactory(
    private val gatewayFactory: SnapshotQueryGatewayFactory
) : AbstractSnapshotQueryServiceFactory() {
    override fun createQueryService(namedAggregate: NamedAggregate): SnapshotQueryService<*> {
        val metadata = namedAggregate.requiredAggregateType<Any>().aggregateMetadata<Any, Any>()
        return LegacySnapshotQueryAdapter(gatewayFactory.create(metadata), metadata)
    }
}

internal class LegacySnapshotQueryAdapter<S : Any>(
    private val gateway: SnapshotQueryGateway<S>,
    private val metadata: AggregateMetadata<*, S>
) : SnapshotQueryService<S> {
    private val materializer = QueryMaterializer(JsonSerializer)
    override val namedAggregate: NamedAggregate = gateway.namedAggregate
    override val name: String = "gateway"

    override fun single(singleQuery: ISingleQuery): Mono<MaterializedSnapshot<S>> {
        val query = singleQuery.toQuery()
        return gateway.firstRecord(query)
            .map { materializer.snapshot(it, metadata) }
            .withLegacyDeletionAccess(query)
    }

    override fun dynamicSingle(singleQuery: ISingleQuery): Mono<DynamicDocument> {
        val query = singleQuery.toQuery()
        return gateway.firstRecord(query).map(::toDynamicDocument).withLegacyDeletionAccess(query)
    }

    override fun list(listQuery: IListQuery): Flux<MaterializedSnapshot<S>> {
        val query = listQuery.toQuery()
        val records = if (listQuery.limit == 0) {
            gateway.streamRecords(query)
        } else {
            gateway.streamRecords(query, listQuery.limit)
        }
        return records.map { materializer.snapshot(it, metadata) }.withLegacyDeletionAccess(query)
    }

    override fun dynamicList(listQuery: IListQuery): Flux<DynamicDocument> {
        val query = listQuery.toQuery()
        val records = if (listQuery.limit == 0) {
            gateway.streamRecords(query)
        } else {
            gateway.streamRecords(query, listQuery.limit)
        }
        return records.map(::toDynamicDocument).withLegacyDeletionAccess(query)
    }

    override fun paged(pagedQuery: IPagedQuery): Mono<PagedList<MaterializedSnapshot<S>>> {
        val query = pagedQuery.toQuery()
        return gateway.pageRecords(
            query,
            pagedQuery.pagination.index,
            pagedQuery.pagination.size
        ).map { page -> PagedList(page.total, page.items.map { materializer.snapshot(it, metadata) }) }
            .withLegacyDeletionAccess(query)
    }

    override fun dynamicPaged(pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>> {
        val query = pagedQuery.toQuery()
        return gateway.pageRecords(
            query,
            pagedQuery.pagination.index,
            pagedQuery.pagination.size
        ).map { page -> PagedList(page.total, page.items.map(::toDynamicDocument)) }
            .withLegacyDeletionAccess(query)
    }

    override fun count(condition: Condition): Mono<Long> {
        val (filter, deletion) = LegacyConditionLowerer.lowerQuery(condition)
        val query = Query(filter = filter, scope = QueryScope(deletion = deletion))
        return gateway.count(query.filter, query.scope, query.budget).withLegacyDeletionAccess(query)
    }

    @Suppress("UNCHECKED_CAST")
    private fun toDynamicDocument(source: ObjectNode): DynamicDocument {
        val record = source.deepCopy()
        TIME_FIELDS.forEach { field ->
            val value = record[field]
            if (value?.isString == true) record.put(field, java.time.Instant.parse(value.asString()).toEpochMilli())
        }
        val values = JsonSerializer.convertValue(record, Map::class.java) as MutableMap<String, Any?>
        return values.toDynamicDocument()
    }

    private companion object {
        val TIME_FIELDS = setOf("firstEventTime", "eventTime", "snapshotTime")
    }
}

private fun ISingleQuery.toQuery(): Query = condition.toQuery(projection, sort)

private fun IListQuery.toQuery(): Query = condition.toQuery(projection, sort)

private fun IPagedQuery.toQuery(): Query = condition.toQuery(projection, sort)

private fun Condition.toQuery(projection: Projection, sort: List<Sort>): Query {
    val (filter, deletion) = LegacyConditionLowerer.lowerQuery(this)
    return Query(filter, projection.toProjection(), sort.toSort(), QueryScope(deletion = deletion))
}

private fun Projection.toProjection(): QueryProjection {
    if (include.isNotEmpty() && exclude.isNotEmpty()) invalidQuery()
    return when {
        include.isNotEmpty() -> QueryProjection.Include(include.mapTo(linkedSetOf(), ::LogicalField))
        exclude.isNotEmpty() -> QueryProjection.Exclude(exclude.mapTo(linkedSetOf(), ::LogicalField))
        else -> QueryProjection.All
    }
}

private fun List<Sort>.toSort(): List<QuerySort> = map { sort ->
    QuerySort(
        LogicalField(sort.field),
        if (sort.direction == Sort.Direction.ASC) QuerySortDirection.ASC else QuerySortDirection.DESC
    )
}

internal object LegacyConditionLowerer {
    fun lowerQuery(condition: Condition): Pair<QueryExpression, DeletionScope> {
        val guarded = condition.guard()
        if (guarded.operator == Operator.DELETED) return MatchAll to guarded.deletionState().toScope()
        if (guarded.operator != Operator.AND) return legacy(listOf(guarded)) to DeletionScope.ACTIVE
        val deletions = guarded.children.filter { it.operator == Operator.DELETED }
        if (deletions.size != 1) return LegacyConditionExpression(guarded) to DeletionScope.ALL
        val remaining = guarded.children - deletions.single()
        return legacy(remaining) to deletions.single().deletionState().toScope()
    }

    private fun legacy(children: List<Condition>): QueryExpression = when (children.size) {
        0 -> MatchAll
        else -> LegacyConditionExpression(
            Condition.and(
                listOf(Condition.deleted(DeletionState.ALL)) + children
            )
        )
    }
}

private fun DeletionState.toScope(): DeletionScope = when (this) {
    DeletionState.ACTIVE -> DeletionScope.ACTIVE
    DeletionState.DELETED -> DeletionScope.DELETED
    DeletionState.ALL -> DeletionScope.ALL
}

private fun Query.requiresLegacyDeletionAccess(): Boolean =
    scope.deletion == DeletionScope.DELETED || scope.deletion == DeletionScope.ALL

private fun Context.grantLegacyDeletionAccess(): Context {
    val authority = QueryContexts.authority(this)
    return QueryContexts.withAuthority(
        authority.copy(
            permissions = authority.permissions + QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS
        )
    )(this)
}

private fun <T : Any> Mono<T>.withLegacyDeletionAccess(query: Query): Mono<T> =
    if (query.requiresLegacyDeletionAccess()) contextWrite(Context::grantLegacyDeletionAccess) else this

private fun <T : Any> Flux<T>.withLegacyDeletionAccess(query: Query): Flux<T> =
    if (query.requiresLegacyDeletionAccess()) contextWrite(Context::grantLegacyDeletionAccess) else this

private fun invalidQuery(): Nothing = throw QueryException(QueryErrorCode.INVALID_QUERY, QueryStage.PREPARATION)
