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

import com.mongodb.ConnectionString
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoClientSettings
import com.mongodb.WriteConcern
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.FindOneAndUpdateOptions
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.client.model.WriteModel
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.log.Partitioner
import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.BsonTimestamp
import org.bson.Document
import org.bson.conversions.Bson
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The raw run on MongoDB: Wow's collections cloned (each index, by `listIndexes`) as `raw_log`, `raw_snapshot` and
 * `raw_partition`, written with documents of the domain's own commits as templates at Wow's write concern
 * (`majority`), each step on a client whose pool is the step's connections:
 *
 * - the log as Wow appends a group: each stream at its partition's next sequence ([Partitioner]), `_id` its position,
 *   all of them one ordered `insertMany`; the sequences come from blocks of [BLOCK] reserved on the partition's counter
 *   by one pipeline `findOneAndUpdate` and taken off its list by one `$pull` once used, as Wow's sequencer does;
 * - the snapshots as an unordered `bulkWrite` of version-guarded upserts in one of four [Update] shapes, M0 Wow's own
 *   filter and pipeline, so each is priced per update as mongod times them.
 *
 * Wow's appender reads an aggregate's previous sequence from its cache of the last ones (a hit in steady state), so no
 * read precedes an append here. A unit counts only when the store wrote it; a write error refuses it.
 */
class RawMongo(private val uri: String, private val databaseName: String) : AutoCloseable {
    /**
     * A snapshot's update, the shapes its store cost is priced in: [M0] Wow's aggregation pipeline `$set` of every
     * field as `$literal` and the stale mark's `$cond`; [M1] a modifier `$set` with no mark in the filter; [M2] M1
     * with `$setOnInsert` for what never changes; [M3] a `replaceOne` of the document.
     */
    enum class Update { M0, M1, M2, M3 }

    /** A case of this store, the state it writes ([bigState]: about 4 KB, else the domain's), and its flush. */
    class Case(val case: RawCase, val bigState: Boolean, val flush: (Writer, Aggregates) -> Flushed)

    private val admin: MongoClient = MongoClients.create(uri)
    private val database: MongoDatabase = admin.getDatabase(databaseName).withWriteConcern(WriteConcern.MAJORITY)
    private lateinit var logTemplates: List<Document>
    private lateinit var snapshotTemplates: List<Document>
    private var partitions = 0
    private val template = AtomicInteger()
    private var sequencer = Sequencer(0)

    /**
     * Up to [count] streams of Wow's measured deposits (version 3 on: an account's opening is 1, its warm-up deposit
     * 2; documents that hold events, so no placeholder), the latest first, and of its snapshots as templates; and its
     * partition count, its counters'.
     */
    fun loadTemplates(count: Int) {
        logTemplates = database.getCollection(LOG)
            .find(Filters.and(Filters.exists("events.0"), Filters.gte(VERSION, MEASURED_VERSION)))
            .sort(Document("commit_time", -1)).limit(count).toList()
        snapshotTemplates = database.getCollection(SNAPSHOT).find().limit(count).toList()
        partitions = database.getCollection(COUNTERS).countDocuments().toInt()
        check(logTemplates.isNotEmpty() && snapshotTemplates.isNotEmpty() && partitions > 0) {
            "Wow's collections hold no documents."
        }
    }

    /**
     * The cases: [WOW_TODAY] (today's shape, the calibration: the log's group of [logBatch] and their snapshots
     * [snapshotBatch] a `bulkWrite` in [Update.M0]), the log alone, each [Update] at each of [batches] updates a
     * `bulkWrite` with the domain's state and with a 4 KB one, the log's insert and its snapshots' `bulkWrite` in one
     * multi-document transaction against the same calls back to back, and a point read.
     */
    fun cases(logBatch: Int, snapshotBatch: Int, batches: List<Int>): List<Case> = buildList {
        add(
            Case(RawCase(WOW_TODAY, COMMAND, logBatch, "today: the calibration"), false) { w, a ->
                val commands = a.take(logBatch)
                val appended = w.insert(commands)
                var saved = Flushed(0)
                var from = 0
                for (n in groupsOf(logBatch, snapshotBatch)) {
                    saved += w.write(commands.subList(from, from + n), Update.M0, bigState = false)
                    from += n
                }
                both(appended, saved)
            }
        )
        add(
            Case(RawCase("log-only", COMMAND, logBatch, "reference: the log's group alone"), false) { w, a ->
                w.insert(a.take(logBatch))
            }
        )
        for (bigState in listOf(false, true)) {
            for (update in Update.entries) {
                for (n in batches) {
                    val name = "${update.name.lowercase()}-$n-${if (bigState) "state-4k" else "state"}"
                    val prices = if (update == Update.M0) "M0: today" else "B: ${update.name}"
                    add(
                        Case(
                            RawCase(name, "update", n, prices),
                            bigState
                        ) { w, a -> w.write(a.take(n), update, bigState) }
                    )
                }
            }
        }
        add(
            Case(RawCase(C_TRANSACTION, COMMAND, logBatch, "C: one multi-document transaction"), false) { w, a ->
                val commands = a.take(logBatch)
                requireNotNull(w.session).withTransaction<Flushed> {
                    both(w.insert(commands), w.write(commands, Update.M0, bigState = false))
                }
            }
        )
        add(
            Case(RawCase(C_BACK_TO_BACK, COMMAND, logBatch, "C's writes back to back, no transaction"), false) { w, a ->
                val commands = a.take(logBatch)
                both(w.insert(commands), w.write(commands, Update.M0, bigState = false))
            }
        )
        add(
            Case(RawCase("point-read", "read", 1, "reference: a snapshot by its key"), false) { w, a ->
                val found = w.snapshots.find(Filters.eq("_id", keyOf(tenant(), a.next().id))).first() != null
                if (found) Flushed(1) else Flushed(0, refused = 1)
            }
        )
    }

    /** A command counts once its stream and its snapshot were both written. */
    private fun both(appended: Flushed, saved: Flushed) =
        Flushed(minOf(appended.written, saved.written), appended.refused + saved.refused)

    /**
     * Fresh `raw_*` collections with Wow's indexes, the partitions' counters at 0, and [aggregates] snapshots at
     * version 1, of the 4 KB state when [bigState].
     */
    fun prepare(aggregates: Int, bigState: Boolean) {
        for ((source, target) in listOf(LOG to RAW_LOG, SNAPSHOT to RAW_SNAPSHOT, COUNTERS to RAW_COUNTERS)) {
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
        database.getCollection(RAW_COUNTERS).insertMany(
            List(partitions) { Document("_id", it).append(SEQUENCE, 0L).append("parent", null).append("epoch", 0) },
        )
        sequencer = Sequencer(partitions)
        val first = snapshotTemplates.first()
        (0 until aggregates).chunked(PREPARE_BATCH).forEach { numbers ->
            database.getCollection(RAW_SNAPSHOT).insertMany(
                numbers.map { number ->
                    val id = Aggregates.idOf(number)
                    Document(first).apply {
                        this["_id"] = keyOf(first.getString(TENANT_ID), id)
                        this[AGGREGATE_ID] = id
                        this[VERSION] = 1
                        if (bigState) this[STATE] = BIG_STATE
                    }
                },
                InsertManyOptions().ordered(false),
            )
        }
    }

    /** A client for a step of [connections] workers: its pool their number, so none waits for a connection. */
    fun client(connections: Int): MongoClient = MongoClients.create(
        MongoClientSettings.builder()
            .applyConnectionString(ConnectionString(uri))
            .applyToConnectionPoolSettings { it.maxSize(connections).minSize(connections) }
            .build(),
    )

    /** A worker's writer on [client], with a session when [case] writes in a transaction. */
    fun writer(client: MongoClient, case: RawCase): Writer =
        Writer(client, if (case.name == C_TRANSACTION) client.startSession() else null)

    /** One worker's writes, on its step's client, in [session] when it has one. */
    inner class Writer(client: MongoClient, val session: ClientSession?) : AutoCloseable {
        private val database = client.getDatabase(databaseName).withWriteConcern(WriteConcern.MAJORITY)
        private val log: MongoCollection<Document> = database.getCollection(RAW_LOG)
        val snapshots: MongoCollection<Document> = database.getCollection(RAW_SNAPSHOT)
        private val counters: MongoCollection<Document> = database.getCollection(RAW_COUNTERS)

        /** [commands]' streams as Wow's group: sequenced, then one ordered `insertMany`; the ones before a failure. */
        fun insert(commands: List<Aggregates.Command>): Flushed {
            val documents = commands.map { command ->
                val partition = Partitioner.partitionOf(ACCOUNT_TYPE.ref(tenant(), command.id), partitions)
                logOf(nextTemplate(), command, partition, sequencer.next(partition, counters))
            }
            return try {
                if (session != null) log.insertMany(session, documents) else log.insertMany(documents)
                Flushed(commands.size)
            } catch (refused: MongoBulkWriteException) {
                if (refused.writeConcernError != null) throw refused
                val written = refused.writeErrors.minOf { it.index }
                Flushed(written, commands.size - written)
            }
        }

        /** [commands]' snapshots as one unordered `bulkWrite` of [update]'s shape; the ones without a write error. */
        fun write(commands: List<Aggregates.Command>, update: Update, bigState: Boolean): Flushed {
            val first = snapshotTemplates[Math.floorMod(template.get(), snapshotTemplates.size)]
            val models = commands.map { modelOf(first, it, update, bigState) }
            return try {
                if (session != null) {
                    snapshots.bulkWrite(session, models, UNORDERED)
                } else {
                    snapshots.bulkWrite(models, UNORDERED)
                }
                Flushed(commands.size)
            } catch (refused: MongoBulkWriteException) {
                if (refused.writeConcernError != null) throw refused
                Flushed(commands.size - refused.writeErrors.size, refused.writeErrors.size)
            }
        }

        override fun close() {
            session?.close()
        }
    }

    /**
     * The sequences of each partition, as Wow's sequencer gives them on one node: from a block of [BLOCK] reserved on
     * the counter with one pipeline `findOneAndUpdate` (the block added to the counter's list, the sequence moved past
     * it), the used block taken off the list with one `$pull`.
     */
    private class Sequencer(partitions: Int) {
        private class Lane {
            val lock = ReentrantLock()
            var next = 1L
            var to = 0L
        }

        private val lanes = List(partitions) { Lane() }

        fun next(partition: Int, counters: MongoCollection<Document>): Long {
            val lane = lanes[partition]
            return lane.lock.withLock {
                if (lane.next > lane.to) {
                    val used = lane.to.takeIf { it > 0 }?.let { it - BLOCK + 1 }
                    lane.to = reserve(counters, partition)
                    lane.next = lane.to - BLOCK + 1
                    used?.let { from ->
                        counters.updateOne(
                            Filters.eq("_id", partition),
                            Updates.pull(BLOCKS, Document(NODE, NODE_NAME).append(FROM, from)),
                        )
                    }
                }
                lane.next++
            }
        }

        private fun reserve(counters: MongoCollection<Document>, partition: Int): Long {
            val add = Document(NODE, NODE_NAME)
                .append(FROM, Document("\$add", listOf("\$$SEQUENCE", 1L)))
                .append(TO, Document("\$add", listOf("\$$SEQUENCE", BLOCK)))
                .append(COLLECTIONS, listOf(RAW_LOG))
            val open = Document("\$ifNull", listOf("\$$BLOCKS", emptyList<Any>()))
            val blocks = Document("\$concatArrays", listOf(open, listOf(add)))
            val sequence = Document("\$add", listOf("\$$SEQUENCE", BLOCK))
            val update = listOf(Document("\$set", Document(BLOCKS, blocks).append(SEQUENCE, sequence)))
            return counters.findOneAndUpdate(
                Filters.eq("_id", partition),
                update,
                FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER),
            )!!.getLong(SEQUENCE)
        }
    }

    private fun nextTemplate(): Document = logTemplates[Math.floorMod(template.getAndIncrement(), logTemplates.size)]

    private fun tenant(): String = snapshotTemplates.first().getString(TENANT_ID)

    private fun logOf(template: Document, command: Aggregates.Command, partition: Int, sequence: Long): Document =
        Document(template).apply {
            this["_id"] = positionId(partition, sequence)
            this["partition"] = partition
            this[SEQUENCE] = sequence
            this[AGGREGATE_ID] = command.id
            this[VERSION] = command.version
            this["command_id"] = GlobalIdGenerator.UUID_V7.next()
            this["request_id"] = GlobalIdGenerator.UUID_V7.next()
            this["commit_time"] = System.currentTimeMillis()
        }

    /** [command]'s snapshot save in the shape of [update], guarded by a lower version of no higher state revision. */
    private fun modelOf(
        template: Document,
        command: Aggregates.Command,
        update: Update,
        bigState: Boolean,
    ): WriteModel<Document> {
        val tenant = template.getString(TENANT_ID)
        val revision = template.getInteger(STATE_REVISION, 1)
        val fields = Document(template).apply {
            remove("_id")
            remove(STALE_VERSION)
            this[AGGREGATE_ID] = command.id
            this[VERSION] = command.version
            this["last_event_time"] = System.currentTimeMillis()
            if (bigState) this[STATE] = BIG_STATE
        }
        val guards = listOf(Filters.lt(VERSION, command.version), Filters.lte(STATE_REVISION, revision))
        val key = Filters.eq("_id", keyOf(tenant, command.id))
        return when (update) {
            Update.M0 -> UpdateOneModel(
                guardOf(tenant, command, revision),
                listOf<Bson>(Document("\$set", pipelineSet(fields, command.version))),
                UPSERT,
            )
            Update.M1 -> UpdateOneModel(
                Filters.and(guardOf(tenant, command, revision), Filters.exists(STALE_VERSION, false)),
                Document("\$set", fields.without(TENANT_ID, AGGREGATE_ID)),
                UPSERT,
            )
            Update.M2 -> UpdateOneModel(
                Filters.and(listOf(key) + guards),
                Document("\$set", fields.without(*ON_INSERT.toTypedArray()))
                    .append("\$setOnInsert", Document(fields.filterKeys { it in ON_INSERT })),
                UPSERT,
            )
            Update.M3 -> ReplaceOneModel(Filters.and(listOf(key) + guards), fields, ReplaceOptions().upsert(true))
        }
    }

    /** Wow's save filter (MongoSnapshotStore): the key, the tenant and the id, a lower version, no higher revision. */
    private fun guardOf(tenant: String, command: Aggregates.Command, revision: Int): Bson =
        BsonDocument("_id", BsonDocument(KEY_TENANT, BsonString(tenant)).append(KEY_AGGREGATE, BsonString(command.id)))
            .append(TENANT_ID, BsonString(tenant))
            .append(AGGREGATE_ID, BsonString(command.id))
            .append(VERSION, BsonDocument("\$lt", BsonInt32(command.version)))
            .append(STATE_REVISION, BsonDocument("\$lte", BsonInt32(revision)))

    /** Wow's pipeline `$set`: every field as `$literal`, and the stale mark kept only above [version]. */
    private fun pipelineSet(fields: Document, version: Int): Document = Document().apply {
        for ((field, value) in fields.without(TENANT_ID, AGGREGATE_ID)) this[field] = Document("\$literal", value)
        val unmarked = BsonDocument("\$lte", BsonArray(listOf(BsonString("\$$STALE_VERSION"), BsonInt32(version))))
        this[STALE_VERSION] = Document("\$cond", listOf(unmarked, "\$\$REMOVE", "\$$STALE_VERSION"))
    }

    private fun Document.without(vararg keys: String): Document = Document(filterKeys { it !in keys })

    private fun keyOf(tenant: String, id: String) = Document(KEY_TENANT, tenant).append(KEY_AGGREGATE, id)

    /**
     * MongoDB's cumulative counters: the journal's syncs, their time, writes and bytes; the oplog's bytes; the
     * operations; and the snapshot collection's write latency (`latencyStats`, microseconds) and its writes.
     */
    fun counters(): Map<String, Double> {
        val status = database.runCommand(Document("serverStatus", 1))
        val journal = (status["wiredTiger"] as Document?)?.get("log") as Document?
        val ops = status["opcounters"] as Document?
        val oplog = admin.getDatabase("local").runCommand(Document("collStats", "oplog.rs"))
        val latency = database.getCollection(RAW_SNAPSHOT)
            .aggregate(listOf(Document("\$collStats", Document("latencyStats", Document()))))
            .firstOrNull()?.let { (it["latencyStats"] as Document?)?.get("writes") as Document? }
        fun number(document: Document?, key: String): Double? = (document?.get(key) as Number?)?.toDouble()
        return buildMap {
            number(journal, "log sync operations")?.let { put("journalSyncs", it) }
            number(journal, "log sync time duration (usecs)")?.let { put("journalSyncMs", it / MICROS_PER_MILLI) }
            number(journal, "log write operations")?.let { put("journalWrites", it) }
            number(journal, "log bytes written")?.let { put("journalBytes", it) }
            number(oplog, "size")?.let { put("oplogBytes", it) }
            number(ops, "insert")?.let { put("inserts", it) }
            number(ops, "update")?.let { put("updates", it) }
            number(ops, "command")?.let { put("commands", it) }
            number(latency, "latency")?.let { put("snapshotWriteMicros", it) }
            number(latency, "ops")?.let { put("snapshotWrites", it) }
        }
    }

    /**
     * Each of Wow's and the clone's collections' inserts, updates and removes (the `top` command): where a
     * calibration's gap is.
     */
    fun tables(): Map<String, Double> {
        val totals = admin.getDatabase("admin").runCommand(Document("top", 1))["totals"] as Document?
            ?: return emptyMap()
        return buildMap {
            for ((namespace, value) in totals) {
                if (!namespace.contains(".wow_") && !namespace.contains(".raw_")) continue
                val counts = value as Document
                for (kind in listOf("insert", "update", "remove", "queries", "commands")) {
                    val count = (counts[kind] as Document?)?.get("count") as Number? ?: continue
                    put("$namespace $kind", count.toDouble())
                }
            }
        }
    }

    /** The mean latency of the snapshot collection's writes over a step, microseconds, from the counters' growth. */
    fun ratios(growth: Map<String, Double>): Map<String, Double> {
        val writes = growth["snapshotWrites"] ?: 0.0
        if (writes <= 0) return emptyMap()
        return mapOf("snapshotWriteMicros" to (growth["snapshotWriteMicros"] ?: 0.0) / writes)
    }

    /**
     * The server's version and the settings a ceiling is quoted with: its storage engine, journal and write concern,
     * and the log's partitions and sequence block.
     */
    fun settings(): Map<String, Any?> {
        val build = database.runCommand(Document("buildInfo", 1))
        val status = database.runCommand(Document("serverStatus", 1))
        val adminDatabase = admin.getDatabase("admin")
        val options = adminDatabase.runCommand(Document("getCmdLineOpts", 1))
        val replica = runCatching { adminDatabase.runCommand(Document("replSetGetConfig", 1)) }.getOrNull()
        val config = replica?.get("config") as Document?
        return mapOf(
            "version" to build.getString("version"),
            "storageEngine" to (status["storageEngine"] as Document?)?.getString("name"),
            "writeConcern" to "majority",
            "journal" to ((options["parsed"] as Document?)?.get("storage") as Document?)?.get("journal")?.toString(),
            "writeConcernMajorityJournalDefault" to config?.get("writeConcernMajorityJournalDefault"),
            "replicaSetMembers" to (config?.get("members") as List<*>?)?.size,
            "partitions" to partitions,
            "sequenceBlock" to BLOCK,
        )
    }

    /**
     * The oplog's bytes of the node's own collections (its checkpoints and leases: not an aggregate's, whose
     * collections are `<prefix>__<context>__<aggregate>`) written between [fromMillis] and [toMillis], as
     * `ownOplogBytes`: what Wow's oplog holds that the clone's does not.
     */
    fun ownOplog(fromMillis: Long, toMillis: Long): Map<String, Double> {
        val window = Filters.and(
            Filters.gte("ts", BsonTimestamp((fromMillis / MILLIS).toInt(), 0)),
            Filters.lte("ts", BsonTimestamp((toMillis / MILLIS).toInt(), 0)),
            Filters.regex("ns", "^$databaseName\\.wow_(?!.*__)"),
        )
        val sum = Document("\$sum", Document("\$bsonSize", "\$\$ROOT"))
        val total = admin.getDatabase("local").getCollection("oplog.rs")
            .aggregate(
                listOf(Document("\$match", window), Document("\$group", Document("_id", null).append("bytes", sum)))
            )
            .firstOrNull()?.get("bytes") as Number?
        return mapOf("ownOplogBytes" to (total?.toDouble() ?: 0.0))
    }

    override fun close() = admin.close()

    companion object {
        const val LOG = "wow_log__transfer__account"
        const val SNAPSHOT = "wow_snapshot__transfer__account"
        const val COUNTERS = "wow_log__transfer__partition"
        const val RAW_LOG = "raw_log"
        const val RAW_SNAPSHOT = "raw_snapshot"
        const val RAW_COUNTERS = "raw_partition"
        const val WOW_TODAY = "wow-today"
        const val C_TRANSACTION = "c-transaction"
        const val C_BACK_TO_BACK = "c-back-to-back"

        /** Wow's block of sequences (MongoEventLog.BLOCK). */
        const val BLOCK = 256L

        /** Wow's log position: the partition above the sequence's 51 bits (MongoEventLog.positionId). */
        fun positionId(partition: Int, sequence: Long): Long = (partition.toLong() shl SEQUENCE_BITS) or sequence

        private const val SEQUENCE_BITS = 51
        private const val MEASURED_VERSION = 3
        private const val MILLIS = 1_000L
        private const val COMMAND = "command"
        private const val TENANT_ID = "tenant_id"
        private const val AGGREGATE_ID = "aggregate_id"
        private const val VERSION = "version"
        private const val SEQUENCE = "sequence"
        private const val STATE = "state"
        private const val STATE_REVISION = "state_revision"
        private const val STALE_VERSION = "stale_version"
        private const val KEY_TENANT = "t"
        private const val KEY_AGGREGATE = "a"
        private const val BLOCKS = "blocks"
        private const val NODE = "node"
        private const val FROM = "from"
        private const val TO = "to"
        private const val COLLECTIONS = "collections"
        private const val NODE_NAME = "raw"
        private const val PREPARE_BATCH = 1_000
        private const val MICROS_PER_MILLI = 1_000.0
        private const val BIG_STATE_BYTES = 4_096
        private val UPSERT = UpdateOptions().upsert(true)
        private val UNORDERED = BulkWriteOptions().ordered(false)

        /** What never changes after an aggregate's first commit, set only on the upsert's insert in [Update.M2]. */
        private val ON_INSERT = listOf("first_event_time", "first_operator", TENANT_ID, AGGREGATE_ID)

        /** A state of about 4 KB: notes of fixed text, so no store compresses them away. */
        private val BIG_STATE = Document(
            "notes",
            List(BIG_STATE_BYTES / 64) { index -> (0 until 64).map { 'a' + (index * 7 + it) % 26 }.joinToString("") },
        )
    }
}
