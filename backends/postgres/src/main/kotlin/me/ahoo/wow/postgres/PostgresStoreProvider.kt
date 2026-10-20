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

import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.query.threadsAwaitingConnection
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import javax.sql.DataSource

/**
 * The PostgreSQL stores (`wow.stores.<name>.type: postgres`, review R3.3-1), each over the application's `DataSource`
 * that its `data-source` option names, else the only one, and its queries over the one its `query-data-source` option
 * names (review R3.4-2), else the same. Found by `ServiceLoader`.
 */
internal class PostgresStoreProvider : StoreProvider {
    override val type: String = TYPE

    override val requirement: String = "wow-postgres on the classpath and a DataSource bean"

    override fun available(resources: StoreResources): Boolean = resources.has(DataSource::class.java)

    override fun open(config: StoreConfig): Store {
        val dataSource =
            config.resources.resource(DataSource::class.java, config.option(DATA_SOURCE), "${config.key}.$DATA_SOURCE")
        val queries = config.option(QUERY_DATA_SOURCE)?.let { name ->
            config.resources.resource(DataSource::class.java, name, "${config.key}.$QUERY_DATA_SOURCE")
        }
        return PostgresStore(config, dataSource, queries ?: dataSource)
    }

    companion object {
        const val TYPE = "postgres"

        /** The option naming the store's `DataSource` bean. */
        const val DATA_SOURCE = "data-source"

        /**
         * The option naming the `DataSource` bean the store's snapshot and `events` queries read through (review
         * R3.4-2): a pool of their own, so a query never holds a connection the appends wait for, or a read replica's.
         */
        const val QUERY_DATA_SOURCE = "query-data-source"
    }
}

/**
 * A store on PostgreSQL over [dataSource]: each context its own log, snapshot and timer tables; the checkpoints,
 * leases, failure ledger and reservations shared. Its log appends straight to the database (decision D10): one
 * autocommit statement allocates the sequence, so the append queue is off by default. Its queries read through
 * [queryDataSource], [dataSource] unless the store names another (review R3.4-2).
 */
internal class PostgresStore(
    config: StoreConfig,
    val dataSource: DataSource,
    val queryDataSource: DataSource = dataSource,
) : Store(config) {
    private val ddl = config.ddl

    /** Whether the queries draw from the pool the appends draw from. */
    val queriesShareAppends: Boolean get() = queryDataSource === dataSource

    override val location: Any get() = dataSource

    override val queuesAppends: Boolean get() = false

    /** HikariCP's threads awaiting a connection of the appends' pool (decision D252); null for another pool. */
    override fun waitingForConnection(): Int? = dataSource.threadsAwaitingConnection()

    override fun openCheckpoints(): CheckpointStore =
        PostgresCheckpointStore(dataSource, ddl = ddl).also { schema(it::createSchema) }

    override fun openLeases(): LeaseStore = PostgresLeaseStore(dataSource, ddl = ddl).also { schema(it::createSchema) }

    override fun openLedger(): FailureLedger =
        PostgresFailureLedger(dataSource, service = config.service, ddl = ddl).also { schema(it::createSchema) }

    override fun openReservations(): ReservationStore =
        PostgresReservationStore(dataSource, ddl = ddl).also { schema(it::createSchema) }

    override fun openLog(context: String, partitions: Int): EventLog =
        PostgresEventLog(
            dataSource,
            context,
            partitions,
            pollInterval = config.polling.interval,
            maxPollInterval = config.polling.maxInterval,
            ddl = ddl,
        ).also { schema(it::createSchema) }

    override fun openSnapshots(context: String): SnapshotStore =
        PostgresSnapshotStore(dataSource, context, ddl = ddl).also { schema(it::createSchema) }

    override fun openTimers(context: String): TimerStore =
        PostgresTimerStore(dataSource, context, ddl = ddl).also { schema(it::createSchema) }
}
