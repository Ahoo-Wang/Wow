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
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotTables
import me.ahoo.wow.query.AbstractQueryBackendFactory
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.spi.VirtualThreads
import javax.sql.DataSource

/**
 * The snapshot query storage of one bounded context on PostgreSQL: the [PostgresQueryBackend] of each of its
 * aggregate types, over that aggregate's own snapshot table, which `PostgresSnapshotStore` writes (decision D161), with
 * the facts [PostgresQuerySchemaAdapter] reads from the table; and the management of the expression indexes its
 * aggregates declare ([manageIndexes], decision D151).
 */
@InternalWowApi
class PostgresSnapshotQueryStorage @JvmOverloads constructor(
    private val dataSource: DataSource,
    val context: String,
    val options: PostgresQueryOptions = PostgresQueryOptions(),
    private val dispatcher: CoroutineDispatcher = VirtualThreads.dispatcher,
) : AbstractQueryBackendFactory<PostgresQueryBackend>() {
    /** The snapshot table of [aggregateName], quoted. */
    fun table(aggregateName: String): String = PostgresSnapshotTables(context, aggregateName).snapshot

    override fun createBinding(aggregateType: AggregateType): QueryBackendBinding<PostgresQueryBackend> {
        require(aggregateType.context == context) { "$aggregateType is not in context $context." }
        val tables = PostgresSnapshotTables(context, aggregateType.name)
        return QueryBackendBinding(
            PostgresQueryBackend(aggregateType, dataSource, tables.snapshot, options, dispatcher),
            PostgresQuerySchemaAdapter(dataSource, tables, dispatcher),
        )
    }

    /**
     * Manages [indexes] (planned by [PostgresQueryIndexes.plan] for aggregates of this context) as
     * [PostgresQueryOptions.indexDdl] says. Blocking: a host calls it once at startup, after the tables exist.
     */
    fun manageIndexes(indexes: List<PostgresQueryIndex>): PostgresQueryIndexReport =
        PostgresQueryIndexes.manage(dataSource, options.indexDdl, indexes)
}
