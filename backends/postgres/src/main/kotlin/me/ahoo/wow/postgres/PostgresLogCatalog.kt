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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.Ddl
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.schema.EnsuredOnce
import org.postgresql.util.PSQLException
import java.sql.Connection
import javax.sql.DataSource

/**
 * The catalogue of a context's log on PostgreSQL: its partition counters and registry ([createSchema]), and the
 * table of each aggregate type, created and registered once in one transaction under an advisory lock when the log
 * first touches the type ([tableOf]), found in the registry when another node created it ([existing], [register]),
 * and made sure of again when it was dropped since ([withTable]).
 */
internal class PostgresLogCatalog(
    private val dataSource: DataSource,
    private val context: String,
    private val partitions: Int,
    private val ddl: Ddl,
    private val tables: PostgresEventLogTables,
) {
    /** The aggregate tables whose creation and registration this log has made sure of. */
    private val ensured = EnsuredOnce<String, AggregateLogTable>()

    /** The access schema the node serves for each aggregate, recorded as its walk when its table is created. */
    @Volatile
    var served: Map<String, String> = emptyMap()

    /** The aggregate tables of the context, as of the last read of the registry, in registry order. */
    @Volatile
    var registered: List<AggregateLogTable>? = null
        private set

    /** See [PostgresEventLog.createSchema]. */
    fun createSchema() {
        dataSource.autoCommitted { connection ->
            val structures = listOf(tables.partitionsBase, PostgresEventLogTables.REGISTRY)
                .map { it to PostgresEventLogTables.FORMAT }
            PostgresFormat.schema(connection, ddl, structures) {
                PostgresSchema.execute(connection, tables.ddl())
            }
            if (ddl.creates) {
                connection.createStatement().use { it.execute(tables.initializePartitions(partitions)) }
                // Read again on first use, with the tables created from now on.
                registered = null
            }
            connection.prepareStatement("SELECT count(*) FROM ${tables.partitions}").use { query ->
                query.executeQuery().use { rows ->
                    rows.next()
                    val existing = rows.getInt(1)
                    check(existing == partitions) {
                        "The log of $context has $existing partitions, not $partitions: changing it is a repartition."
                    }
                }
            }
        }
    }

    /** See [PostgresEventLog.createTable]. */
    fun createTable(aggregateName: String) {
        dataSource.autoCommitted { tableOf(it, aggregateName) }
    }

    /** The table of [aggregate], created and registered when this log has not yet made sure it is. */
    fun tableOf(connection: Connection, aggregate: String): AggregateLogTable =
        ensured.get(aggregate) { ensure(connection, tables.aggregate(aggregate)) }

    fun tableOf(connection: Connection, type: AggregateType): AggregateLogTable {
        require(type.context == context) { "$type is not in context $context." }
        return tableOf(connection, type.name)
    }

    /**
     * Runs [block] on [type]'s table. When the table is gone (`42P01`: dropped since this log made sure of it, by a
     * test or a migration), the statement ran nothing: it makes sure of the table again and runs [block] once more.
     * Being inline, [block] is compiled in twice at every call site, and its second copy runs only on that path, on the
     * table just made again, so a test reaches its rows with a writer between the making and the read
     * (`PostgresAggregateReadsTest`).
     */
    // Inline, as it was a private member of the log: every append and read runs through it, and a lambda passed to a
    // function that is not inline is an object per call. Being internal, it reaches the cache through [forget].
    internal inline fun <T> withTable(
        connection: Connection,
        type: AggregateType,
        block: (AggregateLogTable) -> T,
    ): T {
        val table = tableOf(connection, type)
        return try {
            block(table)
        } catch (failure: PSQLException) {
            if (failure.sqlState != UNDEFINED_TABLE) throw failure
            forget(type.name, table)
            block(tableOf(connection, type))
        }
    }

    /**
     * Claims [table]'s format, creates it and records it in the registry, in one transaction under an advisory lock,
     * so that nodes creating tables at once neither race the format record nor two aggregates take one table name.
     */
    private fun ensure(connection: Connection, table: AggregateLogTable): AggregateLogTable = table.also {
        PostgresFormat.schema(connection, ddl, listOf(table.base to PostgresEventLogTables.FORMAT)) {
            PostgresSchema.execute(connection, listOf(table.ddl()))
            connection.prepareStatement(tables.register).use { register ->
                register.setString(1, context)
                register.setString(2, table.aggregate)
                register.setString(3, table.base)
                val created = served[table.aggregate]?.let { PostgresAccessWalks.textOf(AccessWalk.created(it)) }
                register.setString(4, created)
                register.executeUpdate()
            }
            val holder = connection.prepareStatement(tables.holder).use { query ->
                query.setString(1, table.base)
                query.executeQuery().use { rows ->
                    rows.next()
                    rows.getString(1) to rows.getString(2)
                }
            }
            check(holder == context to table.aggregate) {
                "The aggregate ${table.aggregate} of $context takes the log table ${table.base}, " +
                    "which the aggregate ${holder.second} of ${holder.first} has."
            }
        }
    }

    /**
     * The table of [type] when it exists: one this log made sure of, or one the registry lists, read again when this
     * log does not know it; null before its first append. It creates nothing.
     */
    fun existing(connection: Connection, type: AggregateType): AggregateLogTable? {
        require(type.context == context) { "$type is not in context $context." }
        return (ensured.values + registered.orEmpty()).firstOrNull { it.aggregate == type.name }
            ?: register(connection).firstOrNull { it.aggregate == type.name }
    }

    /** Reads the registry again: the aggregate tables of the context, in registry order. */
    fun register(connection: Connection): List<AggregateLogTable> {
        val names = connection.prepareStatement(tables.registered).use { query ->
            query.setString(1, context)
            query.executeQuery().use { rows -> rows.map { it.getString(1) } }
        }
        return names.map { tables.aggregate(it) }.also { registered = it }
    }

    /** Forgets that [aggregate]'s [table] was made sure of: it is made sure of again on its next use. */
    fun forget(aggregate: String, table: AggregateLogTable) {
        ensured.forget(aggregate, table)
    }

    internal companion object {
        /** SQLSTATE `42P01`: a table the statement names does not exist; the statement ran nothing. */
        const val UNDEFINED_TABLE = "42P01"
    }
}
