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

import me.ahoo.wow.query.QueryBackend
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.dataset.AbstractDatasetQueryBackendFactory
import me.ahoo.wow.query.dataset.DatasetQueryBackend
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * The in-memory reference dataset storage: tables of rows, each row a record with its fields at the root, and the
 * [InMemoryDatasetQueryBackend] of each dataset bound to one, which passes
 * [DatasetQueryBackendSpec][me.ahoo.wow.query.tck.DatasetQueryBackendSpec]. A dataset names its table in its
 * storage binding, `{"table": "<name>"}` ([binding]).
 *
 * [delay] holds every primitive back that long before it answers, as [InMemoryQueryStorage] does.
 */
class InMemoryDatasetStorage(
    private val delay: Duration = Duration.ZERO,
) : AbstractDatasetQueryBackendFactory() {
    private val tables = ConcurrentHashMap<String, List<ObjectNode>>()

    /** Replaces the rows of [table]; returns the storage binding a dataset of that table declares. */
    fun put(table: String, rows: List<ObjectNode>): ObjectNode {
        tables[table] = rows.map(ObjectNode::deepCopy)
        return binding(table)
    }

    /** The rows of [table], each a fresh tree; none when there is no such table. */
    fun rows(table: String): List<ObjectNode> = tables[table].orEmpty().map(ObjectNode::deepCopy)

    override fun createBinding(dataset: QueryDataset): QueryBackendBinding<DatasetQueryBackend> {
        val table = dataset.storage.get(TABLE)?.takeIf { it.isString }?.stringValue()
        require(table != null) { "Dataset [${dataset.name}] names no in-memory [$TABLE] in its storage binding." }
        val backend = InMemoryQueryBackend(dataset.name, { rows(table) }, delay)
        return QueryBackendBinding(InMemoryDatasetQueryBackend(backend), InMemoryQueryStorageAdapter)
    }

    companion object {
        const val TABLE: String = "table"

        /** The storage binding of [table]. */
        @JvmStatic
        fun binding(table: String): ObjectNode = WowJson.mapper.createObjectNode().put(TABLE, table)
    }
}

/** The dataset query backend of one in-memory table: the reference backend over the table's rows. */
class InMemoryDatasetQueryBackend internal constructor(
    private val delegate: InMemoryQueryBackend,
) : DatasetQueryBackend,
    QueryBackend by delegate {
    /** The timeout of every query this backend received, in order. */
    val timeouts: List<Duration?>
        get() = delegate.timeouts
}
