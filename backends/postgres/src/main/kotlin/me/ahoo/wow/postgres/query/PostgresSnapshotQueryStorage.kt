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

package me.ahoo.wow.postgres.query

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.postgres.VirtualThreads
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.AbstractQueryBackendFactory
import me.ahoo.wow.query.QueryBackendBinding
import javax.sql.DataSource

/**
 * The snapshot query storage of one bounded context on PostgreSQL: the [PostgresSnapshotQueryBackend] of each of its
 * aggregate types, over the snapshot table `PostgresSnapshotStore` writes, with the facts of
 * [PostgresSnapshotStorageAdapter]; and the management of the expression indexes its aggregates declare
 * ([manageIndexes], decision D151).
 */
class PostgresSnapshotQueryStorage @JvmOverloads constructor(
    private val dataSource: DataSource,
    val context: String,
    val options: PostgresQueryOptions = PostgresQueryOptions(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : AbstractQueryBackendFactory<PostgresSnapshotQueryBackend>() {
    private val tables = PostgresSnapshotTables(context)

    /** The snapshot table, quoted. */
    val table: String
        get() = tables.snapshot

    override fun createBinding(aggregateType: AggregateType): QueryBackendBinding<PostgresSnapshotQueryBackend> {
        require(aggregateType.context == context) { "$aggregateType is not in context $context." }
        return QueryBackendBinding(
            PostgresSnapshotQueryBackend(aggregateType, dataSource, tables, options, dispatcher),
            PostgresSnapshotStorageAdapter,
        )
    }

    /**
     * Manages [indexes] (planned by [PostgresQueryIndexes.plan] for this context) as [PostgresQueryOptions.indexDdl]
     * says. Blocking: a host calls it once at startup, after the snapshot table exists.
     */
    fun manageIndexes(indexes: List<PostgresQueryIndex>): PostgresQueryIndexReport =
        PostgresQueryIndexes.manage(dataSource, tables, options.indexDdl, indexes)
}
