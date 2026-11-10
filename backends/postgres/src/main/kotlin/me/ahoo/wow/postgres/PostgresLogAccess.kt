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

package me.ahoo.wow.postgres

import kotlinx.coroutines.CoroutineDispatcher
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AggregatePage
import javax.sql.DataSource

/**
 * The access attributes of a [PostgresEventLog]: the stamps of its streams, the pages of a type's aggregates, and the
 * registry's access walks.
 */
internal class PostgresLogAccess(
    private val dataSource: DataSource,
    private val dispatcher: CoroutineDispatcher,
    private val context: String,
    private val catalog: PostgresLogCatalog,
    private val tables: PostgresEventLogTables,
) {
    /** One statement by the version key, autocommitted: a stamp of a range writes it whole or not at all. */
    suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
        if (from >= until) return 0
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                catalog.withTable(connection, ref.type) { table ->
                    connection.prepareStatement(table.stamp).use { update ->
                        update.setString(1, AccessValues.text(access))
                        update.setInt(2, asOf)
                        update.setString(3, ref.id)
                        update.setString(4, ref.tenantId)
                        update.setInt(5, from)
                        update.setInt(6, until)
                        update.setInt(7, asOf)
                        update.executeUpdate()
                    }
                }
            }
        }
    }

    /**
     * A statement a page: each aggregate's first stream, by the version key, its cursor [AggregatePage.cursorOf]; none
     * of a type whose table does not exist, which it does not create.
     */
    suspend fun aggregates(type: AggregateType, after: String?, limit: Int): AggregatePage {
        require(limit > 0) { "A page holds at least one aggregate." }
        val key = after?.let(AggregatePage::keyOf)
        val refs = dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                val table = catalog.existing(connection, type) ?: return@autoCommitted emptyList()
                connection.prepareStatement(if (key == null) table.firstAggregates else table.aggregatesAfter).use {
                    var index = 1
                    key?.let { (id, tenant) ->
                        it.setString(index++, tenant)
                        it.setString(index++, id)
                    }
                    it.setInt(index, limit)
                    it.executeQuery().use { rows -> rows.map { type.ref(it.getString(2), it.getString(1)) } }
                }
            }
        }
        return AggregatePage.of(refs, limit)
    }

    /** [ranges], of distinct streams: one autocommitted statement per aggregate type. */
    suspend fun stampAll(ranges: List<AccessStamp>): Int {
        return dispatcher.jdbc {
            dataSource.autoCommitted { connection ->
                ranges.groupBy { it.ref.type }.entries.sumOf { (type, group) ->
                    catalog.withTable(connection, type) { table ->
                        connection.prepareStatement(table.stampRows).use { update ->
                            fun array(kind: String, values: List<Any>) =
                                connection.createArrayOf(kind, values.toTypedArray())
                            update.setArray(1, array("text", group.map { AccessValues.text(it.access) }))
                            update.setArray(2, array("int4", group.map { it.asOf }))
                            update.setArray(3, array("text", group.map { it.ref.id }))
                            update.setArray(4, array("text", group.map { it.ref.tenantId }))
                            update.setArray(5, array("int4", group.map { it.from }))
                            update.setArray(6, array("int4", group.map { it.until }))
                            update.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    /** Whether [type]'s table exists: this log made sure of it, or the registry lists it. */
    suspend fun holds(type: AggregateType): Boolean = dispatcher.jdbc {
        dataSource.autoCommitted { catalog.existing(it, type) != null }
    }

    /**
     * The registry's `access_walk` of [type] ([PostgresAccessWalks]); none before the type's table is created, which
     * the read does not do: a type has a registry row from its table's creation on.
     */
    suspend fun walk(type: AggregateType): AccessWalk? = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.walk).use { query ->
                query.setString(1, context)
                query.setString(2, type.name)
                query.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1)?.let(PostgresAccessWalks::walkOf) else null
                }
            }
        }
    }

    /**
     * One conditional update of the registry row: concurrent claims serialize on it. A type whose table does not exist
     * has no row, and the update matches none.
     */
    suspend fun claim(type: AggregateType, walk: AccessWalk, expected: String?): Boolean = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.claimWalk).use { update ->
                update.setString(1, PostgresAccessWalks.textOf(walk))
                update.setString(2, context)
                update.setString(3, type.name)
                update.setNullableString(4, expected)
                update.executeUpdate() == 1
            }
        }
    }

    suspend fun advance(
        type: AggregateType,
        schema: String,
        cursor: String?,
        walked: Long,
        completed: Boolean,
    ): Boolean = dispatcher.jdbc {
        dataSource.autoCommitted { connection ->
            connection.prepareStatement(tables.advanceWalk).use { update ->
                update.setString(1, PostgresAccessWalks.progressOf(cursor, walked, completed))
                update.setString(2, context)
                update.setString(3, type.name)
                update.setString(4, schema)
                update.executeUpdate() == 1
            }
        }
    }
}
