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

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.mongo.log.MongoEventLog
import me.ahoo.wow.postgres.PostgresEventLog
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.log.EventLog
import org.postgresql.ds.PGSimpleDataSource
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
 *               "password": "env:WOW_PG_PASSWORD" },
 *   "partitions": { "example": 16 }
 * }
 * ```
 *
 * - [manifests] are the V10 Manifests of the services the streams go to (each a merged `wow-manifest.json`).
 * - [source] is the 9.x MongoDB event store ([SourceConfig]); [target] the V10 log ([TargetConfig]).
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
 * The 9.x MongoDB event store: its connection [uri] and [database]. The tool does not write to it unless
 * [createCommitTimeIndex] is set: then the tail creates the `(createTime, _id)` index it reads by where it is missing;
 * otherwise the tail refuses to start without it, naming the collections and the command that creates it, so the
 * operator decides when to build it on a large collection.
 */
data class SourceConfig(val uri: String, val database: String, val createCommitTimeIndex: Boolean = false)

/**
 * The V10 log: [type] `postgres` (with [jdbcUrl], [username] and [password]) or `mongo` (with [uri] and [database],
 * a replica set). Per-aggregate log stores (decision D177) are migrated by one run per store, each with a map of the
 * aggregates that store holds.
 */
data class TargetConfig(
    val type: String,
    val jdbcUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val uri: String? = null,
    val database: String? = null,
) {
    init {
        require(type == POSTGRES || type == MONGO) { "A target is of type $POSTGRES or $MONGO, not $type." }
        require(type != POSTGRES || jdbcUrl != null) { "A $POSTGRES target needs a jdbcUrl." }
        require(type != MONGO || (uri != null && database != null)) { "A $MONGO target needs a uri and a database." }
    }

    companion object {
        const val POSTGRES = "postgres"
        const val MONGO = "mongo"
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

/** What a run connects to: the 9.x store, and the V10 log of each context. */
interface MigrationConnections : Closeable {
    val source: NineXEventSource

    fun logOf(context: String): EventLog
}

/**
 * The connections [config] names: a 9.x MongoDB store, and a V10 log on PostgreSQL or MongoDB whose schema each
 * context's first use creates (with the configured partition count, which an existing log must already have).
 */
class ConfiguredConnections(private val config: MigrationConfig) : MigrationConnections {
    private val sourceClient: MongoClient = MongoClients.create(
        requireNotNull(MigrationConfig.resolve(config.source.uri)),
    )
    override val source: MongoNineXEventSource =
        MongoNineXEventSource(sourceClient.getDatabase(config.source.database))
    private val targetClient: MongoClient? = config.target.uri?.takeIf { config.target.type == TargetConfig.MONGO }
        ?.let { MongoClients.create(requireNotNull(MigrationConfig.resolve(it))) }
    private val dataSource by lazy {
        PGSimpleDataSource().apply {
            setURL(config.target.jdbcUrl)
            config.target.username?.let { user = it }
            MigrationConfig.resolve(config.target.password)?.let { password = it }
        }
    }
    private val logs = ConcurrentHashMap<String, EventLog>()

    override fun logOf(context: String): EventLog = logs.computeIfAbsent(context) {
        val partitions = config.partitionsOf(context)
        if (targetClient != null) {
            MongoEventLog(targetClient, requireNotNull(config.target.database), context, partitions)
                .also { it.createSchema() }
        } else {
            PostgresEventLog(dataSource, context, partitions).also { it.createSchema() }
        }
    }

    override fun close() {
        sourceClient.close()
        targetClient?.close()
    }
}
