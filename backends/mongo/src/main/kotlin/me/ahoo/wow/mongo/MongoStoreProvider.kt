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
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.mongo.timer.MongoTimerStore
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.spi.store.Opened
import me.ahoo.wow.spi.store.Store
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.spi.store.StoreResources
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.spi.timer.TimerStore
import kotlin.time.Duration
import org.bson.Document

/**
 * The MongoDB stores (`wow.stores.<name>.type: mongo`), each over the application's `MongoClient` that
 * its `client` option names (else the only one), in the database its `database` option names (`wow` when not set);
 * its `append-timeout` option bounds an append's insert ([MongoEventLog.DEFAULT_APPEND_TIMEOUT] when not set), and
 * its `sharding` option, `auto` (the default) or `off`, whether it shards its collections on a sharded cluster
 * ([MongoSharding]), and its `operation-timeout` option how long each of its other calls may take
 * ([MongoTimeouts.OPERATION] when not set).
 * Found by `ServiceLoader`.
 */
internal class MongoStoreProvider : StoreProvider {
    override val type: String = TYPE

    override val requirement: String = "wow-mongo on the classpath and a MongoClient bean"

    override fun available(resources: StoreResources): Boolean = resources.has(MongoClient::class.java)

    override fun open(config: StoreConfig): Store = MongoStore(
        config,
        config.resources.resource(MongoClient::class.java, config.option(CLIENT), "${config.key}.$CLIENT"),
        config.option(DATABASE) ?: DEFAULT_DATABASE,
        config.option(APPEND_TIMEOUT)?.let(Duration::parse) ?: MongoEventLog.DEFAULT_APPEND_TIMEOUT,
        MongoSharding.of(config.option(SHARDING)),
        config.option(OPERATION_TIMEOUT)?.let(Duration::parse) ?: MongoTimeouts.OPERATION,
    )

    companion object {
        const val TYPE = "mongo"

        /** The option naming the store's `MongoClient` bean. */
        const val CLIENT = "client"

        /** The option naming the store's database. */
        const val DATABASE = "database"
        const val DEFAULT_DATABASE = "wow"

        /** The option bounding an append's insert, such as `5s`. */
        const val APPEND_TIMEOUT = "append-timeout"

        /** The option saying whether the store shards its collections on a sharded cluster: `auto` or `off`. */
        const val SHARDING = "sharding"

        /** The option bounding each of the store's calls besides appends and the schema's, such as `30s`. */
        const val OPERATION_TIMEOUT = "operation-timeout"
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
) : Store(config) {
    private val ddl = config.ddl

    /** A `ping` of the store's database: the store answers. */
    override suspend fun probe() {
        withContext(Dispatchers.IO) { client.getDatabase(database).runCommand(Document("ping", 1)) }
    }

    override val location: Any get() = client to database

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
