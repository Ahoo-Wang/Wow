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

package me.ahoo.wow.mongo

import com.mongodb.client.MongoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.query.MongoQueries
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.spi.config.ProviderOption
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
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
import org.bson.Document
import org.slf4j.LoggerFactory
import kotlin.time.Duration

/**
 * The MongoDB stores (`wow.stores.<name>.type: mongo`), each over the application's `MongoClient` that
 * its `client` option names (else the only one), in the database its `database` option names (`wow` when not set);
 * its `append-timeout` option bounds an append's insert ([MongoEventLog.DEFAULT_APPEND_TIMEOUT] when not set), and
 * its `sharding` option, `auto` (the default) or `off`, whether it shards its collections on a sharded cluster
 * ([MongoSharding]), and its `operation-timeout` option how long each of its other calls may take
 * ([MongoTimeouts.OPERATION] when not set). Its pool is the client's [MongoPoolGauge] bean its `pool-gauge` option names
 * (else the only one); without one, it says nothing of its pool, and its queries take the default bound.
 * Found by `ServiceLoader`.
 */
internal class MongoStoreProvider : StoreProvider {
    override val type: String = TYPE

    override val requirement: String = "wow-mongo on the classpath and a MongoClient bean"

    override fun available(resources: StoreResources): Boolean = resources.has(MongoClient::class.java)

    override val options: List<ProviderOption<*>> = listOf(
        CLIENT,
        DATABASE,
        APPEND_TIMEOUT,
        SHARDING,
        OPERATION_TIMEOUT,
        POOL_GAUGE,
        QUERY_READ_PREFERENCE,
    )

    override fun open(config: StoreConfig): Store = MongoStore(
        config,
        config.resources.resource(MongoClient::class.java, config.option(CLIENT), "${config.key}.${CLIENT.key}"),
        config.option(DATABASE),
        config.option(APPEND_TIMEOUT),
        config.option(SHARDING),
        config.option(OPERATION_TIMEOUT),
        gaugeOf(config),
        config.option(QUERY_READ_PREFERENCE),
    )

    /**
     * The store's [MongoPoolGauge], when the application has one its client was built with; said once, when the store
     * opens, when it has none.
     */
    private fun gaugeOf(config: StoreConfig): MongoPoolGauge? {
        val gauge = if (config.resources.has(MongoPoolGauge::class.java)) {
            config.resources.resource(
                MongoPoolGauge::class.java,
                config.option(POOL_GAUGE),
                "${config.key}.${POOL_GAUGE.key}"
            )
        } else {
            null
        }
        if (gauge?.maxConnections == null) {
            log.info(
                "The MongoDB store {} has no MongoPoolGauge its client was built with: its pool is unknown, so its " +
                    "queries take the default bound and its write pressure is its append latency alone.",
                config.name,
            )
            return null
        }
        return gauge
    }

    companion object {
        const val TYPE = "mongo"

        const val DEFAULT_DATABASE = "wow"

        /** The option naming the store's `MongoClient` bean. */
        val CLIENT = ProviderOption.string(
            "client",
            "the name of the MongoClient bean the store opens over; the only one when not set.",
        ).perStore()

        /** The option naming the store's database. */
        val DATABASE = ProviderOption.string(
            "database",
            "the database of the log and the stores on MongoDB: unset, the one spring.mongodb.uri names, else wow.",
            DEFAULT_DATABASE,
        )

        /** The option bounding an append's insert. */
        val APPEND_TIMEOUT = ProviderOption.duration(
            "append-timeout",
            "how long an append's insert may take: below the default wait timeout, so a command appending during an " +
                "outage is answered before its wait ends.",
            MongoEventLog.DEFAULT_APPEND_TIMEOUT,
        )

        /** The option saying whether the store shards its collections on a sharded cluster. */
        val SHARDING = ProviderOption.enumeration(
            "sharding",
            "whether a store on a sharded cluster shards its log, snapshot, timer and reservation collections when it " +
                "creates them: auto when connected to a mongos with ddl auto, none for a cluster its operators shard.",
            MongoSharding::class.java,
            MongoSharding.AUTO,
        )

        /** The option bounding each of the store's calls besides appends and the schema's. */
        val OPERATION_TIMEOUT = ProviderOption.duration(
            "operation-timeout",
            "how long each call of a MongoDB store may take besides appends (append-timeout) and the schema's " +
                "commands (5 minutes): a reply lost on the way fails the call instead of blocking it forever.",
            MongoTimeouts.OPERATION,
        )

        /** The option naming the store's [MongoPoolGauge] bean, when the application has several. */
        val POOL_GAUGE = ProviderOption.string(
            "pool-gauge",
            "the name of the MongoPoolGauge bean the store's client was built with, when the application has several.",
        ).perStore()

        /** The option of the read preference of the store's snapshot and `events` queries. */
        val QUERY_READ_PREFERENCE = ProviderOption.string(
            "query-read-preference",
            "the read preference of the snapshot and events queries of a MongoDB store (secondaryPreferred, say), so " +
                "a free filter scans a secondary rather than the primary the log appends to; the primary when not " +
                "set. A snapshot read from a secondary may be behind the one a command's wait waited for.",
        )

        private val log = LoggerFactory.getLogger(MongoStoreProvider::class.java)
    }
}

/**
 * A store on MongoDB over [client], in [database]: each context its own log, snapshot and timer collections; the
 * checkpoints, leases, failure ledger and reservations shared. An append is one insert at a sequence of the node's
 * block; the partition append queue gathers a partition's appends into one `insertMany` per collection.
 */
internal class MongoStore(
    config: StoreConfig,
    val client: MongoClient,
    val database: String,
    private val appendTimeout: Duration = MongoEventLog.DEFAULT_APPEND_TIMEOUT,
    private val sharding: MongoSharding = MongoSharding.AUTO,
    private val operationTimeout: Duration = MongoTimeouts.OPERATION,
    gauge: MongoPoolGauge? = null,
    /** The read preference of the store's snapshot and `events` queries; the primary when not set. */
    val queryReadPreference: String? = null,
) : Store(config) {
    private val ddl = config.ddl
    private val pool = gauge?.let(::MongoPool)

    /** A `ping` of the store's database: the store answers. */
    override suspend fun probe() {
        withContext(Dispatchers.IO) { client.getDatabase(database).runCommand(Document("ping", 1)) }
    }

    private val queries = MongoQueries(this)

    override val location: Any get() = client to database

    /** Its queries ([MongoQueries]) and, when it has a [MongoPoolGauge], its pool ([MongoPool]). */
    override fun <F : Any> facet(type: Class<F>): F? = when (type) {
        StoreQueries::class.java -> type.cast(queries)
        StorePool::class.java -> pool?.let(type::cast)
        else -> null
    }

    override fun openCheckpoints(): Opened<CheckpointStore> =
        MongoCheckpointStore(
            client,
            database,
            ddl = ddl,
            operationTimeout = operationTimeout
        ).let { Opened.of(it, it::createSchema) }

    override fun openLeases(): Opened<LeaseStore> =
        MongoLeaseStore(
            client,
            database,
            clock = config.clock,
            ddl = ddl,
            operationTimeout = operationTimeout
        ).let { Opened.of(it, it::createSchema) }

    override fun openLedger(): Opened<FailureLedger> =
        MongoFailureLedger(
            client,
            database,
            service = config.service,
            ddl = ddl,
            operationTimeout = operationTimeout,
        ).let { Opened.of(it, it::createSchema) }

    override fun openReservations(): Opened<ReservationStore> =
        MongoReservationStore(
            client,
            database,
            clock = config.clock,
            ddl = ddl,
            sharding = sharding,
            operationTimeout = operationTimeout
        ).let { Opened.of(it, it::createSchema) }

    /** Whether the server holds a log's guarantees: asked once, by the first log the store opens. */
    private val replicated = lazy { MongoTopology.requireReplicated(client, config.key, config.development) }

    override fun openLog(context: String, partitions: Int): Opened<EventLog> {
        replicated.value
        return MongoEventLog(
            client,
            database,
            context,
            partitions,
            pollInterval = config.polling.interval,
            maxPollInterval = config.polling.maxInterval,
            ddl = ddl,
            appendTimeout = appendTimeout,
            sharding = sharding,
            operationTimeout = operationTimeout,
        ).let { Opened.of(it, it::createSchema) }
    }

    override fun openSnapshots(context: String): Opened<SnapshotStore> =
        MongoSnapshotStore(
            client,
            database,
            context,
            ddl = ddl,
            sharding = sharding,
            operationTimeout = operationTimeout,
        ).let { Opened.of(it, it::createSchema) }

    override fun openTimers(context: String, partitions: Int): Opened<TimerStore> =
        MongoTimerStore(
            client,
            database,
            context,
            partitions,
            ddl = ddl,
            sharding = sharding,
            operationTimeout = operationTimeout,
        ).let { Opened.of(it, it::createSchema) }
}

/**
 * The pool of a MongoDB store, its [StorePool] facet, as its client's [MongoPoolGauge] says it: the queries and the
 * appends draw from the client's one pool per server, so the queries share it.
 */
internal class MongoPool(private val gauge: MongoPoolGauge) : StorePool {
    override val queryConnections: Int? get() = gauge.maxConnections

    override val sharedWithAppends: Boolean get() = true

    override fun waitingForConnection(): Int? = gauge.waiting()
}
