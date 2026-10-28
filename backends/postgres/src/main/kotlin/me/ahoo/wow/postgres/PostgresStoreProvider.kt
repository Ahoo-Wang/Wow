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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.query.PostgresPool
import me.ahoo.wow.postgres.query.PostgresQueries
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.postgres.timer.PostgresTimerStore
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * The PostgreSQL stores (`wow.stores.<name>.type: postgres`), each over the application's `DataSource`
 * that its `data-source` option names, else the only one, and its queries over the one its `query-data-source` option
 * names, else the same; its `operation-timeout` option bounds each of its other calls
 * ([PostgresTimeouts.OPERATION] when not set). Found by `ServiceLoader`.
 */
internal class PostgresStoreProvider : StoreProvider {
    override val type: String = TYPE

    override val requirement: String = "wow-postgres on the classpath and a DataSource bean"

    override fun available(resources: StoreResources): Boolean = resources.has(DataSource::class.java)

    override val options: List<ProviderOption<*>> = listOf(DATA_SOURCE, QUERY_DATA_SOURCE, OPERATION_TIMEOUT)

    override fun open(config: StoreConfig): Store {
        val dataSource = config.resources.resource(
            DataSource::class.java,
            config.option(DATA_SOURCE),
            "${config.key}.${DATA_SOURCE.key}",
        )
        val queries = config.option(QUERY_DATA_SOURCE)?.let { name ->
            config.resources.resource(DataSource::class.java, name, "${config.key}.${QUERY_DATA_SOURCE.key}")
        }
        val timeout = checkNotNull(config.option(OPERATION_TIMEOUT))
        return PostgresStore(config, dataSource, queries ?: dataSource, timeout)
    }

    companion object {
        const val TYPE = "postgres"

        /** The option naming the store's `DataSource` bean. */
        val DATA_SOURCE = ProviderOption.string(
            "data-source",
            "the name of the DataSource bean the store opens over; the only one when not set.",
        ).perStore()

        /**
         * The option naming the `DataSource` bean the store's snapshot and `events` queries read through:
         * a pool of their own, so a query never holds a connection the appends wait for, or a read replica's.
         */
        val QUERY_DATA_SOURCE = ProviderOption.string(
            "query-data-source",
            "the name of the DataSource bean the store's snapshot and events queries read through, so they do not " +
                "take the appends' connections; the store's data-source when not set.",
        ).perStore()

        /** The option bounding each of the store's calls besides the schema's and the queries'. */
        val OPERATION_TIMEOUT = ProviderOption.duration(
            "operation-timeout",
            "how long each call of a PostgreSQL store may wait for a reply besides the schema's statements " +
                "(5 minutes) and the queries (their own statement_timeout): a reply lost on the way fails the call, " +
                "and its connection, instead of blocking it forever.",
            PostgresTimeouts.OPERATION,
        )
    }
}

/**
 * A store on PostgreSQL over [dataSource]: each context its own log, snapshot and timer tables; the checkpoints,
 * leases, failure ledger and reservations shared. Its log appends straight to the database: one
 * autocommit statement allocates the sequence; under load the node's append queue gathers a partition's appends
 * into one statement. Its queries read through
 * [queryDataSource], [dataSource] unless the store names another.
 */
internal class PostgresStore(
    config: StoreConfig,
    val dataSource: DataSource,
    val queryDataSource: DataSource = dataSource,
    operationTimeout: Duration = PostgresTimeouts.OPERATION,
) : Store(config) {
    private val ddl = config.ddl

    /** [dataSource] for the stores: each connection bounded by the operation timeout ([PostgresTimeouts]). */
    private val timed = PostgresTimeouts.timed(dataSource, operationTimeout)

    private val pool = PostgresPool(dataSource, queryDataSource)
    private val queries = PostgresQueries(this)

    override val location: Any get() = dataSource

    /** Its pools ([PostgresPool]) and its queries ([PostgresQueries]). */
    override fun <F : Any> facet(type: Class<F>): F? = when (type) {
        StorePool::class.java -> type.cast(pool)
        StoreQueries::class.java -> type.cast(queries)
        else -> null
    }

    override fun openCheckpoints(): Opened<CheckpointStore> =
        PostgresCheckpointStore(timed, ddl = ddl).let { Opened.of(it, it::createSchema) }

    override fun openLeases(): Opened<LeaseStore> =
        PostgresLeaseStore(timed, clock = config.clock, ddl = ddl).let { Opened.of(it, it::createSchema) }

    override fun openLedger(): Opened<FailureLedger> =
        PostgresFailureLedger(timed, service = config.service, ddl = ddl).let { Opened.of(it, it::createSchema) }

    override fun openReservations(): Opened<ReservationStore> =
        PostgresReservationStore(timed, clock = config.clock, ddl = ddl).let { Opened.of(it, it::createSchema) }

    private val logs = ConcurrentHashMap<String, PostgresEventLog>()
    private val snapshotStores = ConcurrentHashMap<String, PostgresSnapshotStore>()

    /** A pooled connection that is valid within a second: the store answers. */
    override suspend fun probe() {
        val valid = withContext(Dispatchers.IO) { dataSource.connection.use { it.isValid(PROBE_TIMEOUT_SECONDS) } }
        check(valid) { "$this answered no valid connection." }
    }

    /** One statement over this store's log and snapshots of [context], which share its database. */
    override fun openSnapshotTails(context: String): SnapshotTailReader? {
        val log = logs[context] ?: return null
        val snapshots = snapshotStores[context] ?: return null
        return log.snapshotTailsWith(snapshots)
    }

    override fun openLog(context: String, partitions: Int): Opened<EventLog> =
        PostgresEventLog(
            timed,
            context,
            partitions,
            pollInterval = config.polling.interval,
            maxPollInterval = config.polling.maxInterval,
            ddl = ddl,
        ).also { logs[context] = it }.let { Opened.of(it, it::createSchema) }

    override fun openSnapshots(context: String): Opened<SnapshotStore> =
        PostgresSnapshotStore(timed, context, ddl = ddl).also { snapshotStores[context] = it }
            .let { Opened.of(it, it::createSchema) }

    override fun openTimers(context: String, partitions: Int): Opened<TimerStore> =
        PostgresTimerStore(timed, context, ddl = ddl).let { Opened.of(it, it::createSchema) }

    private companion object {
        /** How long a probe's connection has to prove valid, in seconds. */
        const val PROBE_TIMEOUT_SECONDS = 1
    }
}
