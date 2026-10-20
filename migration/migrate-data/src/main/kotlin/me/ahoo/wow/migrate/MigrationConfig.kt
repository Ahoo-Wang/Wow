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

package me.ahoo.wow.migrate

import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.zaxxer.hikari.HikariDataSource
import me.ahoo.wow.elasticsearch.events.ElasticsearchEventSinks
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.mongo.ledger.MongoFailureLedger
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.mongo.reservation.MongoReservationStore
import me.ahoo.wow.mongo.snapshot.MongoSnapshotStore
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.postgres.ledger.PostgresFailureLedger
import me.ahoo.wow.postgres.reservation.PostgresReservationStore
import me.ahoo.wow.postgres.snapshot.PostgresSnapshotStore
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.ledger.FailureLedger
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.reservation.ReservationStore
import me.ahoo.wow.spi.snapshot.SnapshotStore
import org.apache.hc.core5.http.HttpHost
import tools.jackson.databind.DeserializationFeature
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The configuration file of `wow-migrate-data`, JSON. Relative paths are relative to the file; a value written
 * `env:NAME` is read from the environment variable `NAME`, so a password need not be in the file.
 *
 * ```json
 * {
 *   "map": "migration-map.json",
 *   "manifests": ["build/wow/manifest.json"],
 *   "checkpoint": "migrate-data.checkpoint.json",
 *   "source": { "uri": "mongodb://localhost:27017", "database": "example_db" },
 *   "target": { "type": "postgres", "jdbcUrl": "jdbc:postgresql://localhost:5432/wow", "username": "wow",
 *               "password": "env:WOW_PG_PASSWORD", "poolSize": 16 },
 *   "partitions": { "example": 16 },
 *   "classpath": ["libs"],
 *   "compensation": { "database": "compensation_db", "service": "example-service" },
 *   "elasticsearch": { "uri": "http://localhost:9200", "indexPrefix": "" }
 * }
 * ```
 *
 * - [manifests] are the V10 Manifests of the services the streams go to (each a merged `wow-manifest.json`).
 * - [classpath] holds the services' domain modules, each a jar or a directory (of classes, of jars, or both):
 *   `compare` and `snapshot` rebuild states with their V10 models ([MigrationModels]). Empty, the models are those on
 *   the tool's own class path.
 * - [source] is the 9.x MongoDB event store ([SourceConfig]); [target] the V10 log ([TargetConfig]).
 * - [compensation], when given, is the 9.x compensation service's database, whose unresolved executions are imported
 *   into the V10 service's failure ledger ([CompensationConfig]).
 * - [elasticsearch], when given, is the cluster of the services' events indices, which `backfill-tags` re-sinks
 *   ([ElasticsearchConfig]).
 * - [partitions] gives each V10 context its partition count, which must be the services' own: a log refuses another.
 * - [pageSize], [streamBatch], [concurrency] and [lookbackMillis] are [ImportOptions]; [pollIntervalMillis] is the
 *   pause between two tail passes.
 */
data class MigrationConfig(
    val map: String,
    val manifests: List<String>,
    val checkpoint: String = "migrate-data.checkpoint.json",
    val source: SourceConfig,
    val target: TargetConfig,
    val partitions: Map<String, Int> = emptyMap(),
    val pageSize: Int = 500,
    val streamBatch: Int = 200,
    val concurrency: Int = 8,
    val lookbackMillis: Long = 300_000,
    val pollIntervalMillis: Long = 1_000,
    val classpath: List<String> = emptyList(),
    val compensation: CompensationConfig? = null,
    val elasticsearch: ElasticsearchConfig? = null,
) {
    /** The options of an import, a dry run when [dryRun], a final complete pass when [restart]. */
    fun options(dryRun: Boolean = false, restart: Boolean = false): ImportOptions = ImportOptions(
        pageSize = pageSize,
        streamBatch = streamBatch,
        concurrency = concurrency,
        dryRun = dryRun,
        restart = restart,
        lookbackMillis = lookbackMillis,
    )

    /** The partition count of [context]. */
    fun partitionsOf(context: String): Int =
        partitions[context] ?: throw MigrationException("The configuration gives the V10 context $context no partitions.")

    companion object {
        private val READER = WowJson.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()

        /** Reads the configuration file at [path], resolving its relative paths against the file's directory. */
        fun read(path: Path): MigrationConfig {
            val config = READER.readValue(Files.readString(path), MigrationConfig::class.java)
            val directory = path.toAbsolutePath().parent
            return config.copy(
                map = directory.resolve(config.map).toString(),
                manifests = config.manifests.map { directory.resolve(it).toString() },
                checkpoint = directory.resolve(config.checkpoint).toString(),
                classpath = config.classpath.map { directory.resolve(it).toString() },
            )
        }

        /** [value], or the environment variable it names when written `env:NAME`. */
        fun resolve(value: String?, environment: (String) -> String? = System::getenv): String? {
            if (value == null || !value.startsWith(ENV_PREFIX)) {
                return value
            }
            val name = value.removePrefix(ENV_PREFIX)
            return environment(name) ?: throw MigrationException("The environment variable $name is not set.")
        }

        private const val ENV_PREFIX = "env:"
    }
}

/**
 * The Elasticsearch cluster at [uri] whose events indices (decision D235) the services' event search Sinks write,
 * their names prefixed with [indexPrefix] as the store's `index-prefix` prefixes them: `backfill-tags` overwrites the
 * documents indexed there before the indices learned the tags (decision D259), and nothing else.
 */
data class ElasticsearchConfig(val uri: String, val indexPrefix: String = "")

/**
 * The 9.x MongoDB event store: its connection [uri] and [database]. The tool does not write to it unless
 * [createCommitTimeIndex] is set: then the tail creates the `(createTime, _id)` index it reads by where it is missing;
 * otherwise the tail refuses to start without it, naming the collections and the command that creates it, so the
 * operator decides when to build it on a large collection.
 */
data class SourceConfig(val uri: String, val database: String, val createCommitTimeIndex: Boolean = false)

/**
 * The 9.x compensation service's MongoDB [database] (`compensation_db` in its distribution), on the server of [uri],
 * the source's when null; the tool only reads it. Its unresolved executions go to the failure ledger of the V10
 * [service], on the target backend: the ledger the service's host opens, keyed by its name (`wow.service`, else
 * `spring.application.name`).
 */
data class CompensationConfig(val database: String, val service: String, val uri: String? = null) {
    init {
        require(database.isNotBlank()) { "The 9.x compensation database needs a name." }
        require(service.isNotBlank()) { "The 9.x compensation import needs the V10 service whose ledger it writes." }
    }
}

/**
 * The V10 log: [type] `postgres` (with [jdbcUrl], [username] and [password], through a pool of at most [poolSize]
 * connections) or `mongo` (with [uri] and [database], a replica set). Per-aggregate log stores (decision D177) are migrated by one run per store, each with a map of the
 * aggregates that store holds.
 */
data class TargetConfig(
    val type: String,
    val jdbcUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val uri: String? = null,
    val database: String? = null,
    val poolSize: Int = DEFAULT_POOL_SIZE,
) {
    init {
        require(poolSize >= 1) { "A target's pool holds at least one connection." }
        require(type == POSTGRES || type == MONGO) { "A target is of type $POSTGRES or $MONGO, not $type." }
        require(type != POSTGRES || jdbcUrl != null) { "A $POSTGRES target needs a jdbcUrl." }
        require(type != MONGO || (uri != null && database != null)) { "A $MONGO target needs a uri and a database." }
    }

    companion object {
        const val POSTGRES = "postgres"
        const val MONGO = "mongo"

        /** The default [poolSize]: as many connections as the default import and backfill concurrency needs, twice. */
        const val DEFAULT_POOL_SIZE = 16
    }
}

/** Reads the V10 Manifests at [paths] as one: their contexts together. */
fun readManifests(paths: List<String>): Manifest {
    val manifests = paths.map { path ->
        ManifestTree.read(WowJson.mapper.readValue(Files.readString(Path.of(path)), Map::class.java))
    }
    val contexts: List<ContextManifest> = manifests.flatMap { it.contexts }
    return Manifest(contexts = contexts)
}

/** What a run connects to: the 9.x store (its event streams and snapshots), and the V10 log and snapshots of each context. */
interface MigrationConnections : Closeable {
    val source: NineXEventSource

    val snapshots: NineXSnapshotSource

    fun logOf(context: String): EventLog

    fun snapshotStoreOf(context: String): SnapshotStore

    /** The 9.x `PrepareKey`s (finding A4-8). */
    val reservations: NineXReservationSource

    /** The V10 reservation store, one for every context, on the target backend; its schema created. */
    fun reservationStore(): ReservationStore

    /** The 9.x compensation service's executions; null when the configuration names no compensation database. */
    val compensation: NineXCompensationSource? get() = null

    /** The V10 service's failure ledger on the target backend, its schema created; only with [compensation]. */
    fun failureLedger(): FailureLedger = throw MigrationException("The configuration names no 9.x compensation.")

    /** The event search Sinks of the configured Elasticsearch cluster; null when the configuration names none. */
    val eventSinks: ElasticsearchEventSinks? get() = null
}

/**
 * The connections [config] names: a 9.x MongoDB store, and a V10 log on PostgreSQL or MongoDB whose schema each
 * context's first use creates (with the configured partition count, which an existing log must already have), with
 * the snapshot store of each context and the reservation store on the same backend (their default names, as the
 * host's).
 */
class ConfiguredConnections(private val config: MigrationConfig) : MigrationConnections {
    private val sourceClient: MongoClient = MongoClients.create(
        requireNotNull(MigrationConfig.resolve(config.source.uri)),
    )
    override val source: MongoNineXEventSource =
        MongoNineXEventSource(sourceClient.getDatabase(config.source.database))
    override val snapshots: NineXSnapshotSource =
        MongoNineXSnapshotSource(sourceClient.getDatabase(config.source.database))
    private val targetClient: MongoClient? = config.target.uri?.takeIf { config.target.type == TargetConfig.MONGO }
        ?.let { MongoClients.create(requireNotNull(MigrationConfig.resolve(it))) }
    private val dataSource = lazy {
        HikariDataSource().apply {
            jdbcUrl = config.target.jdbcUrl
            config.target.username?.let { username = it }
            MigrationConfig.resolve(config.target.password)?.let { password = it }
            maximumPoolSize = config.target.poolSize
            poolName = "wow-migrate-data"
        }
    }
    private val logs = ConcurrentHashMap<String, EventLog>()

    override fun logOf(context: String): EventLog = logs.computeIfAbsent(context) {
        val partitions = config.partitionsOf(context)
        if (targetClient != null) {
            MongoEventLog(targetClient, requireNotNull(config.target.database), context, partitions)
                .also { it.createSchema() }
        } else {
            PostgresEventLog(dataSource.value, context, partitions).also { it.createSchema() }
        }
    }

    private val snapshotStores = ConcurrentHashMap<String, SnapshotStore>()

    override fun snapshotStoreOf(context: String): SnapshotStore = snapshotStores.computeIfAbsent(context) {
        if (targetClient != null) {
            MongoSnapshotStore(targetClient, requireNotNull(config.target.database), context)
        } else {
            PostgresSnapshotStore(dataSource.value, context)
        }
    }

    override val reservations: NineXReservationSource =
        MongoNineXReservationSource(sourceClient.getDatabase(config.source.database))

    private val reservationStore: ReservationStore by lazy {
        if (targetClient != null) {
            MongoReservationStore(targetClient, requireNotNull(config.target.database)).also { it.createSchema() }
        } else {
            PostgresReservationStore(dataSource.value).also { it.createSchema() }
        }
    }

    override fun reservationStore(): ReservationStore = reservationStore

    private val compensationClient: MongoClient? = config.compensation?.uri
        ?.let { MongoClients.create(requireNotNull(MigrationConfig.resolve(it))) }

    override val compensation: NineXCompensationSource? = config.compensation?.let {
        MongoNineXCompensationSource((compensationClient ?: sourceClient).getDatabase(it.database))
    }

    private val failureLedger: FailureLedger by lazy {
        val service = config.compensation?.service
            ?: throw MigrationException("The configuration names no 9.x compensation.")
        if (targetClient != null) {
            MongoFailureLedger(targetClient, requireNotNull(config.target.database), service = service)
                .also { it.createSchema() }
        } else {
            PostgresFailureLedger(dataSource.value, service = service).also { it.createSchema() }
        }
    }

    override fun failureLedger(): FailureLedger = failureLedger

    private val elasticsearch: Rest5Client? = config.elasticsearch?.let {
        Rest5Client.builder(HttpHost.create(requireNotNull(MigrationConfig.resolve(it.uri)))).build()
    }

    override val eventSinks: ElasticsearchEventSinks? = elasticsearch?.let {
        ElasticsearchEventSinks.of(it, requireNotNull(config.elasticsearch).indexPrefix)
    }

    override fun close() {
        if (dataSource.isInitialized()) dataSource.value.close()
        elasticsearch?.close()
        sourceClient.close()
        compensationClient?.close()
        targetClient?.close()
    }
}
