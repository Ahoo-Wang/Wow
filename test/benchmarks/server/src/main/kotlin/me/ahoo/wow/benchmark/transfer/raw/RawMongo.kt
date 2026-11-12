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

package me.ahoo.wow.benchmark.transfer.raw

import com.mongodb.WriteConcern
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The raw run on MongoDB: Wow's collections cloned (each index, by `listIndexes`) as `raw_log` and `raw_snapshot`,
 * written with documents of the domain's own commits as templates and Wow's write concern (`majority`): the log's
 * `insertMany`, and the snapshots' unordered `bulkWrite` of version-guarded upserts, each a pipeline `$set` of every
 * field as `$literal` (Wow's, [Variant.PIPELINE]) or a plain `$set` ([Variant.SET], a candidate).
 */
class RawMongo(uri: String, databaseName: String) : AutoCloseable {
    /** How a snapshot's update is written. */
    enum class Variant(val key: String) {
        PIPELINE("pipeline"),
        SET("set"),
        ;

        companion object {
            fun of(key: String): Variant = entries.firstOrNull { it.key == key } ?: error("Unknown variant $key.")
        }
    }

    private val client: MongoClient = MongoClients.create(uri)
    private val database: MongoDatabase = client.getDatabase(databaseName).withWriteConcern(WriteConcern.MAJORITY)
    private lateinit var logTemplates: List<Document>
    private lateinit var snapshotTemplates: List<Document>
    private val sequence = AtomicLong()

    /** Reads up to [count] documents of Wow's log and snapshots as templates. */
    fun loadTemplates(count: Int) {
        logTemplates = database.getCollection(LOG).find().sort(Document("_id", -1)).limit(count).toList()
        snapshotTemplates = database.getCollection(SNAPSHOT).find().limit(count).toList()
        check(logTemplates.isNotEmpty() && snapshotTemplates.isNotEmpty()) { "Wow's collections hold no documents." }
    }

    /** Fresh `raw_*` collections with Wow's indexes, and [aggregates] snapshots at version 1. */
    fun prepare(aggregates: Int) {
        for ((source, target) in listOf(LOG to RAW_LOG, SNAPSHOT to RAW_SNAPSHOT)) {
            database.getCollection(target).drop()
            database.createCollection(target)
            for (index in database.getCollection(source).listIndexes()) {
                val name = index.getString("name")
                if (name == "_id_") continue
                val options = IndexOptions().name(name).unique(index.getBoolean("unique", false))
                    .sparse(index.getBoolean("sparse", false))
                (index["partialFilterExpression"] as Document?)?.let { options.partialFilterExpression(it) }
                database.getCollection(target).createIndex(index["key"] as Document, options)
            }
        }
        val template = snapshotTemplates.first()
        val snapshots = database.getCollection(RAW_SNAPSHOT)
        (0 until aggregates).chunked(PREPARE_BATCH).forEach { ids ->
            snapshots.insertMany(ids.map { snapshotOf(template, "raw-$it", 1) }, InsertManyOptions().ordered(false))
        }
    }

    /** One worker of [shape]: [logBatch] commands a flush, their snapshots in groups of [snapshotBatch]. */
    fun worker(shape: Shape, variant: Variant, owned: Aggregates, logBatch: Int, snapshotBatch: Int): RawWorker {
        val log: MongoCollection<Document> = database.getCollection(RAW_LOG)
        val snapshots: MongoCollection<Document> = database.getCollection(RAW_SNAPSHOT)
        val groups = snapshotGroups(logBatch, snapshotBatch, shape.mergesSnapshots)
        val session = if (shape.oneTransaction) client.startSession() else null
        return object : RawWorker {
            private var template = 0

            override fun flush(): Int {
                if (shape == Shape.POINT_READ) {
                    val id = owned.next().id
                    snapshots.find(Filters.eq("_id", keyOf(tenant(), id))).first()
                    return 1
                }
                val commands = List(logBatch) { owned.next().also { it.version++ } }
                val documents = commands.map { logOf(logTemplates[template++ % logTemplates.size], it) }
                val updates = commands.map {
                    updateOf(snapshotTemplates[template % snapshotTemplates.size], it, variant)
                }
                val write = {
                    if (session != null) log.insertMany(session, documents) else log.insertMany(documents)
                    if (shape.writesSnapshots) {
                        var from = 0
                        for (rows in groups) {
                            val batch = updates.subList(from, from + rows)
                            if (session != null) {
                                snapshots.bulkWrite(session, batch, UNORDERED)
                            } else {
                                snapshots.bulkWrite(batch, UNORDERED)
                            }
                            from += rows
                        }
                    }
                }
                if (session != null) session.withTransaction<Unit> { write() } else write()
                return logBatch
            }

            override fun close() {
                session?.close()
            }
        }
    }

    private fun tenant(): String = snapshotTemplates.first().getString(TENANT_ID)

    private fun logOf(template: Document, command: Aggregates.Command): Document = Document(template).apply {
        remove("_id")
        this["partition"] = Math.floorMod(command.id.hashCode(), PARTITIONS)
        this["sequence"] = sequence.incrementAndGet()
        this[AGGREGATE_ID] = command.id
        this["version"] = command.version
        this["command_id"] = UUID.randomUUID().toString()
        this["request_id"] = UUID.randomUUID().toString()
        this["commit_time"] = System.currentTimeMillis()
    }

    private fun snapshotOf(template: Document, id: String, version: Int): Document = Document(template).apply {
        this["_id"] = keyOf(template.getString(TENANT_ID), id)
        this[AGGREGATE_ID] = id
        this["version"] = version
    }

    /** Wow's save of [command]'s snapshot: guarded by a lower version, of no higher state revision; upserted. */
    private fun updateOf(template: Document, command: Aggregates.Command, variant: Variant): UpdateOneModel<Document> {
        val tenant = template.getString(TENANT_ID)
        val fields = Document(template).apply {
            remove("_id")
            this[AGGREGATE_ID] = command.id
            this["version"] = command.version
            this["last_event_time"] = System.currentTimeMillis()
        }
        val revision = template.getInteger(STATE_REVISION, 1)
        val guard = Filters.and(
            Filters.eq("_id", keyOf(tenant, command.id)),
            Filters.eq(TENANT_ID, tenant),
            Filters.eq(AGGREGATE_ID, command.id),
            Filters.lt("version", command.version),
            Filters.lte(STATE_REVISION, revision),
        )
        return when (variant) {
            Variant.PIPELINE -> {
                val set = Document()
                for ((field, value) in fields) set[field] = Document("\$literal", value)
                set[STALE_VERSION] = Document(
                    "\$cond",
                    listOf(
                        Document("\$lte", listOf("\$$STALE_VERSION", command.version)),
                        "\$\$REMOVE",
                        "\$$STALE_VERSION",
                    ),
                )
                UpdateOneModel(guard, listOf<Bson>(Document("\$set", set)), UPSERT)
            }
            Variant.SET -> UpdateOneModel(guard, Document("\$set", fields), UPSERT)
        }
    }

    private fun keyOf(tenant: String, id: String) = Document("t", tenant).append("a", id)

    /** MongoDB's cumulative counters: the journal's syncs, their time and its writes, and the operations. */
    fun counters(): Map<String, Double> {
        val status = database.runCommand(Document("serverStatus", 1))
        val log = (status["wiredTiger"] as Document?)?.get("log") as Document?
        val ops = status["opcounters"] as Document?
        fun number(document: Document?, key: String): Double? = (document?.get(key) as Number?)?.toDouble()
        return buildMap {
            number(log, "log sync operations")?.let { put("journalSyncs", it) }
            number(log, "log sync time duration (usecs)")?.let { put("journalSyncMs", it / MICROS_PER_MILLI) }
            number(log, "log write operations")?.let { put("journalWrites", it) }
            number(log, "log bytes written")?.let { put("journalBytes", it) }
            number(ops, "insert")?.let { put("inserts", it) }
            number(ops, "update")?.let { put("updates", it) }
        }
    }

    override fun close() = client.close()

    companion object {
        const val LOG = "wow_log__transfer__account"
        const val SNAPSHOT = "wow_snapshot__transfer__account"
        const val RAW_LOG = "raw_log"
        const val RAW_SNAPSHOT = "raw_snapshot"
        private const val TENANT_ID = "tenant_id"
        private const val AGGREGATE_ID = "aggregate_id"
        private const val STATE_REVISION = "state_revision"
        private const val STALE_VERSION = "stale_version"
        private const val PARTITIONS = 64
        private const val PREPARE_BATCH = 1_000
        private const val MICROS_PER_MILLI = 1_000.0
        private val UPSERT = UpdateOptions().upsert(true)
        private val UNORDERED = BulkWriteOptions().ordered(false)
    }
}
