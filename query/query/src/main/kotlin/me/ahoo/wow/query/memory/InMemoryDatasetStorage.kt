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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.backend.CachedQueryBindings
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetScope
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory reference dataset storage: tables of rows, each row a record with its fields at the root, and the
 * [InMemoryQueryBackend] of each dataset bound to one, which passes `DatasetQueryBackendSpec` (the `:query`
 * test fixtures). A dataset names its table in its
 * storage binding, `{"table": "<name>"}` ([binding]).
 *
 * It also holds the current access record of each aggregate ([putAccess]), as the ClickHouse Sink's `_state` does: a
 * dataset bound to an aggregate ([me.ahoo.wow.query.dataset.QueryDatasetAccess]) reads each row by the record of its
 * aggregate, the one of the row's tenant (a platform dataset's, [QueryDatasetScope.PLATFORM_TENANT]) and of the id
 * its id column holds ([me.ahoo.wow.query.dataset.DatasetAccess]); a row whose aggregate has none matches no access
 * restriction.
 *
 * [delay] holds every primitive back that long before it answers, as [InMemoryQueryStorage] does.
 */
@InternalWowApi
class InMemoryDatasetStorage(
    private val delay: Duration = Duration.ZERO,
) : CachedQueryBindings<QueryDataset>(QueryDataset::name) {
    private val tables = ConcurrentHashMap<String, List<ObjectNode>>()
    private val access = ConcurrentHashMap<AccessKey, Recorded>()

    /** Replaces the rows of [table]; returns the storage binding a dataset of that table declares. */
    fun put(table: String, rows: List<ObjectNode>): ObjectNode {
        tables[table] = rows.map(ObjectNode::deepCopy)
        return binding(table)
    }

    /** The rows of [table], each a fresh tree; none when there is no such table. */
    fun rows(table: String): List<ObjectNode> = tables[table].orEmpty().map(ObjectNode::deepCopy)

    /**
     * Records the access attributes of [aggregate]'s [aggregateId] in [tenantId] after its commit of [version] (a
     * stream's, the built-in owner and space among them; null for a commit that recorded none), unless a later
     * version is recorded: the newest commit's are the aggregate's current ones, as the Sink's `_state` replaces its
     * rows by version.
     */
    fun putAccess(
        aggregate: AggregateType,
        tenantId: String,
        aggregateId: String,
        version: Int,
        access: Map<String, Any?>?,
    ) {
        val recorded = Recorded(version, access?.let { AccessValues.json(AccessValues.normalized(it)) })
        this.access.merge(AccessKey(aggregate, tenantId, aggregateId), recorded) { current, next ->
            if (next.version >= current.version) next else current
        }
    }

    /** The current access record of [aggregate]'s [aggregateId] in [tenantId]; null when none is recorded. */
    private fun accessOf(aggregate: AggregateType, tenantId: String, aggregateId: String): JsonNode? =
        access[AccessKey(aggregate, tenantId, aggregateId)]?.access

    override fun createBinding(key: QueryDataset): QueryBackendBinding<InMemoryQueryBackend> {
        val dataset = key
        val table = dataset.storage.get(TABLE)?.takeIf { it.isString }?.stringValue()
        require(table != null) { "Dataset [${dataset.name}] names no in-memory [$TABLE] in its storage binding." }
        val backend = InMemoryQueryBackend(dataset.name, { rows(table) }, delay, rowAccess(dataset))
        return QueryBackendBinding(backend, InMemoryDatasetStorageAdapter)
    }

    /** The current access record of the aggregate each row of [dataset] is of; none for a dataset bound to none. */
    private fun rowAccess(dataset: QueryDataset): RowAccess? {
        val bound = dataset.scope.access ?: return null
        val tenant = dataset.scope.tenant
        return RowAccess { row ->
            val tenantId = if (tenant == null) QueryDatasetScope.PLATFORM_TENANT else row.text(tenant)
            val aggregateId = row.text(bound.id)
            if (tenantId == null || aggregateId == null) null else accessOf(bound.aggregate, tenantId, aggregateId)
        }
    }

    private fun ObjectNode.text(field: QueryField<*>): String? =
        valuesAt(field).singleOrNull()?.takeIf { it.isString }?.stringValue()

    private data class AccessKey(val aggregate: AggregateType, val tenantId: String, val aggregateId: String)

    /** The access record of a commit of [version]; null when it recorded none. */
    private class Recorded(val version: Int, val access: ObjectNode?)

    @InternalWowApi
    companion object {
        const val TABLE: String = "table"

        /** The storage binding of [table]. */
        @JvmStatic
        fun binding(table: String): ObjectNode = WowJson.mapper.createObjectNode().put(TABLE, table)
    }
}

/** The current access record of the aggregate a dataset's row is of; null when the storage holds none. */
internal fun interface RowAccess {
    fun of(row: ObjectNode): JsonNode?
}
