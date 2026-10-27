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

package me.ahoo.wow.query.memory

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ICountQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.backend.BackendPage
import me.ahoo.wow.query.backend.CursorPosition
import me.ahoo.wow.query.backend.CursorPositionCodec
import me.ahoo.wow.query.backend.GroupWindow
import me.ahoo.wow.query.backend.PageWindow
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.toQueryRecord
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.storageFamilies
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The in-memory reference query storage: the snapshots of every aggregate type it holds, and the
 * [InMemoryQueryBackend] of each, which passes `QueryBackendSpec` (`:query-tck`). A snapshot replaces the
 * stored one of its ref only with a higher version, as a snapshot store does; a rebuilt one ([rewrite]) also replaces
 * one of the same version. The host serves the snapshot queries of a node on the memory backend from it when
 * `wow.query.memory.enabled` is on: for local runs, demos and tests, whose data does not outlive the node.
 *
 * [delay] holds every primitive back that long before it answers, so tests can let a query outlive its timeout.
 */
class InMemoryQueryStorage(
    private val delay: Duration = Duration.ZERO,
) : QueryBackendFactory<InMemoryQueryBackend> {
    private val snapshots = ConcurrentHashMap<AggregateType, ConcurrentHashMap<AggregateRef, SnapshotRecord>>()
    private val order = ConcurrentHashMap<AggregateType, CopyOnWriteArrayList<AggregateRef>>()
    private val backends = ConcurrentHashMap<AggregateType, QueryBackendBinding<InMemoryQueryBackend>>()

    /** Stores [snapshot] unless a snapshot of the same or a higher version is stored; returns whether it was. */
    fun save(snapshot: SnapshotRecord): Boolean = put(snapshot) { current -> current.version < snapshot.version }

    /**
     * Stores [snapshot], rebuilt from the log, in place of the stored one, as a snapshot store's `rewrite` does: unless
     * a higher version is stored; returns whether it was.
     */
    fun rewrite(snapshot: SnapshotRecord): Boolean = put(snapshot) { current -> current.version <= snapshot.version }

    /** The records of [type], in the order their aggregates were first stored, each a fresh tree. */
    fun records(type: AggregateType): List<ObjectNode> {
        val byRef = snapshots[type] ?: return emptyList()
        return order[type].orEmpty().mapNotNull { byRef[it]?.toQueryRecord() }
    }

    override fun create(aggregateType: AggregateType): QueryBackendBinding<InMemoryQueryBackend> =
        backends.computeIfAbsent(aggregateType) {
            QueryBackendBinding(InMemoryQueryBackend(it, { records(it) }, delay), InMemoryQueryStorageAdapter)
        }

    private fun put(snapshot: SnapshotRecord, replaces: (current: SnapshotRecord) -> Boolean): Boolean {
        val type = snapshot.ref.type
        val byRef = snapshots.computeIfAbsent(type) { ConcurrentHashMap() }
        var written = false
        byRef.compute(snapshot.ref) { _, current ->
            if (current == null || replaces(current)) {
                written = true
                snapshot
            } else {
                current
            }
        }
        if (written) {
            val refs = order.computeIfAbsent(type) { CopyOnWriteArrayList() }
            if (snapshot.ref !in refs) refs += snapshot.ref
        }
        return written
    }
}

/**
 * The query backend of one record set: an aggregate type's snapshots in an [InMemoryQueryStorage], or a dataset's
 * rows in an [InMemoryDatasetStorage]. It evaluates every filter operator but
 * full-text search, sort, offset and keyset pages, bounded counts, projection and aggregation; HAVING, top-N by a
 * metric, dense fill and windows are left to the core ([InMemoryQueryStorageAdapter]). [timeouts] records the timeout
 * of every query it received, so a test can see that the timeout reached the backend.
 */
class InMemoryQueryBackend internal constructor(
    override val aggregateType: AggregateType,
    private val records: suspend () -> List<ObjectNode>,
    private val delay: Duration,
) : QueryBackend {
    override val cursorPositions: CursorPositionCodec = CursorPositionCodec.JSON

    private val received = CopyOnWriteArrayList<Duration?>()

    /** The timeout of every query this backend received, in order; `null` for a query without one. */
    val timeouts: List<Duration?>
        get() = received.toList()

    private suspend fun receive(query: AdmittedQuery<*>) {
        received += query.timeout
        if (!delay.isZero) delay(delay.toMillis())
    }

    private suspend fun matched(query: AdmittedQuery<Queryable<*>>): List<ObjectNode> {
        val filters = InMemoryFilters(query)
        val matched = records().filter { filters.matches(query.query.filter, RecordScope(it)) }
        return matched.sortedWith(comparator(query, query.query.sort))
    }

    override fun stream(query: AdmittedQuery<IListQuery>): Flow<ObjectNode> = flow {
        receive(query)
        val rows = matched(query)
        val limited = if (query.query.limit == 0) rows else rows.take(query.query.limit)
        limited.forEach { emit(project(query, it)) }
    }

    override suspend fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): BackendPage {
        receive(query)
        val rows = matched(query)
        return when (window) {
            is PageWindow.Offset -> {
                val total = if (!window.withTotal) {
                    null
                } else {
                    window.totalLimit?.let { minOf(rows.size.toLong(), it + 1L) } ?: rows.size.toLong()
                }
                BackendPage(rows.drop(window.offset).take(window.limit).map { project(query, it) }, total)
            }
            is PageWindow.Keyset -> {
                val sort = query.query.sort
                val after = window.after?.let { position -> position.values.map { it.positionNode() } }
                val page = rows.filter { after == null || isAfter(query, sort, it, after) }.take(window.limit)
                BackendPage(
                    page.map { project(query, it) },
                    positions = page.map { row ->
                        CursorPosition(sort.map { sortKey(query, it, row).positionValue() })
                    },
                )
            }
        }
    }

    override suspend fun count(query: AdmittedQuery<ICountQuery>): Long {
        receive(query)
        val filters = InMemoryFilters(query)
        val matches = records().count { filters.matches(query.query.filter, RecordScope(it)) }
        // As `count(*)` over `LIMIT limit + 1`: never more than one past the limit.
        return query.query.limit?.let { minOf(matches.toLong(), it + 1L) } ?: matches.toLong()
    }

    override fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flow<ObjectNode> = flow {
        receive(query)
        InMemoryAggregation(query).run(records(), window).forEach { emit(it) }
    }

    private fun sortKey(query: AdmittedQuery<*>, sort: Sort, record: ObjectNode): JsonNode? =
        record.valuesAt(query.field(sort.field).physicalField).sortKey(sort.direction == Sort.Direction.ASC)

    private fun comparator(query: AdmittedQuery<*>, sort: List<Sort>): Comparator<ObjectNode> =
        Comparator { left, right ->
            sort.firstNotNullOfOrNull { order ->
                val comparison = orderOf(sortKey(query, order, left), sortKey(query, order, right))
                (if (order.direction == Sort.Direction.ASC) comparison else -comparison).takeIf { it != 0 }
            } ?: 0
        }

    /** Whether [record] sorts strictly after the position [after] under [sort]. */
    private fun isAfter(
        query: AdmittedQuery<*>,
        sort: List<Sort>,
        record: ObjectNode,
        after: List<JsonNode?>
    ): Boolean {
        sort.forEachIndexed { index, order ->
            val comparison = orderOf(sortKey(query, order, record), after[index])
            val directed = if (order.direction == Sort.Direction.ASC) comparison else -comparison
            if (directed != 0) return directed > 0
        }
        return false
    }

    private fun project(query: AdmittedQuery<Queryable<*>>, record: ObjectNode): ObjectNode {
        val projection: Projection = query.query.projection
        if (projection.include.isEmpty() && projection.exclude.isEmpty()) return record
        val projected = if (projection.include.isEmpty()) {
            record
        } else {
            nodes.objectNode().also { target ->
                projection.include.forEach { include(record, target, query.field(it).physicalField.path.split('.')) }
            }
        }
        projection.exclude.forEach { exclude(projected, query.field(it).physicalField.path.split('.')) }
        return projected
    }

    private fun include(source: JsonNode, target: ObjectNode, path: List<String>) {
        val name = path.first()
        val value = source.get(name) ?: return
        if (path.size == 1) {
            target.set(name, value.deepCopy())
            return
        }
        when {
            value.isObject -> include(value, target.withObjectProperty(name), path.drop(1))
            value.isArray -> {
                val items = target.get(name)?.takeIf { it.isArray } as? tools.jackson.databind.node.ArrayNode
                    ?: target.putArray(name)
                value.forEachIndexed { index, item ->
                    if (!item.isObject) return@forEachIndexed
                    while (items.size() <= index) items.addObject()
                    include(item, items.get(index) as ObjectNode, path.drop(1))
                }
            }
        }
    }

    private fun exclude(node: JsonNode, path: List<String>) {
        when {
            node is ObjectNode && path.size == 1 -> node.remove(path.first())
            node is ObjectNode -> node.get(path.first())?.let { exclude(it, path.drop(1)) }
            node.isArray -> node.forEach { exclude(it, path) }
        }
    }
}

/**
 * The facts of the in-memory storage: every logical path is stored under its own name, and granted each capability
 * the strict storage-family table ([storageFamilies]) allows for its values, full-text search excepted; HAVING, top-N
 * by a metric, dense fill and the windows are RESIDUAL, so the core computes them.
 */
internal object InMemoryQueryStorageAdapter : QueryStorageAdapter {
    private val VALUE_CAPABILITIES = listOf(
        QueryCapability.EXACT_MATCH,
        QueryCapability.LITERAL_MATCH,
        QueryCapability.RANGE,
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
        QueryCapability.AGGREGATE_TERMS,
        QueryCapability.AGGREGATE_NUMERIC,
        QueryCapability.AGGREGATE_TEMPORAL,
    )

    /** What the in-memory storage computes itself and what it leaves to the core. */
    val SUPPORT: StorageSupport = StorageSupport(
        aggregation = AggregationSupport(
            having = SupportMode.RESIDUAL,
            topN = SupportMode.RESIDUAL,
            denseFill = SupportMode.RESIDUAL,
        ),
    )

    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = QueryStorageFacts(
        bindings = logicalSchema.values.filterKeys { it.segments.isNotEmpty() }.mapValues { (path, value) ->
            val capabilities = capabilitiesOf(value)
            QueryValueBindings(capabilities.associateWith { QueryFieldBindingTemplate(path, null) }, path, path)
        },
        storage = SUPPORT,
    )

    private fun capabilitiesOf(value: QueryValueSchema): Set<QueryCapability> {
        val capabilities = linkedSetOf(QueryCapability.PRESENCE)
        if (value.alternativesOrSelf().any { it.kind == QueryValueKind.ARRAY }) {
            capabilities += QueryCapability.ELEMENT_SCOPE
        }
        val scalars = value.operationValues().filter { it.kind != QueryValueKind.NULL }
        if (scalars.isEmpty() || scalars.any { it.kind != QueryValueKind.SCALAR }) return capabilities
        VALUE_CAPABILITIES.forEach { capability ->
            val families = scalars.map { it.storageFamilies(capability) }
            if (families.all { sets -> sets.isNotEmpty() && sets.all(Set<*>::isNotEmpty) }) capabilities += capability
        }
        return capabilities
    }
}

private fun ObjectNode.withObjectProperty(name: String): ObjectNode =
    (get(name) as? ObjectNode) ?: putObject(name)
