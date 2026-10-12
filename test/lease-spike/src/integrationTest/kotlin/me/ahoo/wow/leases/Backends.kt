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

package me.ahoo.wow.leases

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.subscription.MongoCheckpointStore
import me.ahoo.wow.mongo.subscription.MongoLeaseStore
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.subscription.PostgresCheckpointStore
import me.ahoo.wow.postgres.subscription.PostgresLeaseStore
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.subscription.CheckpointStore
import me.ahoo.wow.spi.subscription.LeaseStore
import org.bson.Document
import org.testcontainers.mongodb.MongoDBContainer
import org.testcontainers.postgresql.PostgreSQLContainer

/** The stores one node uses, on its own connections, and how to close them. */
class NodeStores(
    val log: EventLog,
    val leases: LeaseStore,
    val checkpoints: CheckpointStore,
    val ledger: FailureLedger,
    val close: () -> Unit,
)

/** A database in a container: the schema of a context, and per-node clients of its log, leases and checkpoints. */
interface Backend : AutoCloseable {
    val name: String

    /** The image, server version and settings, for the report. */
    val description: String

    fun createSchema(context: String, partitions: Int)

    fun node(context: String, partitions: Int): NodeStores

    /** A lease store on connections of its own, for the observer: its calls are not counted as the nodes'. */
    val observer: LeaseStore
}

class PostgresBackend : Backend {
    private val container = PostgreSQLContainer(IMAGE).withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
        .also { it.start() }
    private val admin = pool(ADMIN_POOL)
    override val observer: LeaseStore = PostgresLeaseStore(admin)
    override val name = "PostgreSQL"
    override val description: String = admin.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SHOW server_version").use { rows ->
                rows.next()
                "$IMAGE (server ${rows.getString(1)}; defaults except max_connections=$MAX_CONNECTIONS)"
            }
        }
    }

    init {
        PostgresLeaseStore(admin).createSchema()
        PostgresCheckpointStore(admin).createSchema()
        PostgresFailureLedger(admin).createSchema()
    }

    override fun createSchema(context: String, partitions: Int) {
        PostgresEventLog(admin, context, partitions).createSchema()
    }

    override fun node(context: String, partitions: Int): NodeStores {
        val dataSource = pool(NODE_POOL)
        return NodeStores(
            log = PostgresEventLog(dataSource, context, partitions),
            leases = PostgresLeaseStore(dataSource),
            checkpoints = PostgresCheckpointStore(dataSource),
            ledger = PostgresFailureLedger(dataSource),
            close = dataSource::close,
        )
    }

    private fun pool(size: Int) = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            maximumPoolSize = size
            minimumIdle = size
        },
    )

    override fun close() {
        admin.close()
        container.stop()
    }

    private companion object {
        const val IMAGE = "postgres:17-alpine"
        const val MAX_CONNECTIONS = 400
        const val NODE_POOL = 16
        const val ADMIN_POOL = 4
    }
}

class MongoBackend : Backend {
    private val container = MongoDBContainer(IMAGE).withReplicaSet().also { it.start() }
    private val admin = client()
    override val observer: LeaseStore = MongoLeaseStore(admin, DATABASE)
    override val name = "MongoDB"
    override val description: String =
        "$IMAGE (server ${admin.getDatabase("admin").runCommand(Document("buildInfo", 1)).getString("version")}; " +
            "single-member replica set, default write concern)"

    init {
        MongoLeaseStore(admin, DATABASE).createSchema()
        MongoCheckpointStore(admin, DATABASE).createSchema()
        MongoFailureLedger(admin, DATABASE).createSchema()
    }

    override fun createSchema(context: String, partitions: Int) {
        MongoEventLog(admin, DATABASE, context, partitions).createSchema()
    }

    override fun node(context: String, partitions: Int): NodeStores {
        val client = client()
        return NodeStores(
            log = PartitionSerializedLog(MongoEventLog(client, DATABASE, context, partitions)),
            leases = MongoLeaseStore(client, DATABASE),
            checkpoints = MongoCheckpointStore(client, DATABASE),
            ledger = MongoFailureLedger(client, DATABASE),
            close = client::close,
        )
    }

    private fun client(): MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(container.replicaSetUrl))
            .applyToConnectionPoolSettings { it.maxSize(NODE_POOL) }
            .build(),
    )

    override fun close() {
        admin.close()
        container.stop()
    }

    private companion object {
        const val IMAGE = "mongo:7.0"
        const val DATABASE = "wow_h6"
        const val NODE_POOL = 32
    }
}

/**
 * Appends to one partition one at a time on this node, standing in for the partition owner's append queue that
 * MongoDB needs (spike H1, finding F1) and the runtime does not have yet: without it, concurrent appends to one
 * partition abort each other's transactions and the log collapses under this spike's load. Two nodes appending to one
 * partition (Local fallback during a change, D71) still contend, as they would in production.
 */
class PartitionSerializedLog(private val delegate: EventLog) : EventLog by delegate {
    private val locks = Array(delegate.partitions) { Mutex() }

    override suspend fun append(stream: EventStreamRecord, transport: Transport?): AppendOutcome =
        locks[Partitioner.partitionOf(stream.ref, delegate.partitions)].withLock { delegate.append(stream, transport) }
}
