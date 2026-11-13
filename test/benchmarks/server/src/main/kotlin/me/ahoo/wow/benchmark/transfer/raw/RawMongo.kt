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
import com.mongodb.client.model.ReturnDocument
import com.mongodb.client.model.Updates
import me.ahoo.wow.benchmark.transfer.TransferContext.ACCOUNT_TYPE
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.AGGREGATE_ID
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.BIG_STATE
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.STATE
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.TENANT_ID
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.Update
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.VERSION
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.keyOf
import me.ahoo.wow.benchmark.transfer.raw.MongoShapes.modelOf
import me.ahoo.wow.spi.id.GlobalIdGenerator
import me.ahoo.wow.spi.log.Partitioner
import org.bson.BsonTimestamp
import org.bson.Document
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
    /** A case of this store, the state it writes ([bigState]: about 4 KB, else the domain's), and its flush. */
    class Case(
        val case: RawCase,
        val bigState: Boolean,
        val shape: WowShape.Options? = null,
        val flush: (Writer, Aggregates) -> Flushed,
    )

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
        addAll(shapeCases())
        add(
            Case(RawCase("point-read", "read", 1, "reference: a snapshot by its key"), false) { w, a ->
                val found = w.snapshots.find(Filters.eq("_id", keyOf(tenant(), a.next().id))).first() != null
                if (found) Flushed(1) else Flushed(0, refused = 1)
            }
        )
    }

    /** Wow's own peak shape ([WowShape]) at calls of up to 64, 256 and 512 streams. */
    private fun shapeCases(): List<Case> = buildList {
        for (merge in WowShape.MERGES) {
            val prices = "Wow's peak shape: two appenders, calls of up to $merge streams; two writers of up to " +
                "${WowShape.WRITER_MAX} updates"
            add(
                Case(
                    RawCase("wow-shape-$merge", COMMAND, 1, prices, wowShape = true),
                    false,
                    WowShape.Options(merge)
                ) { _, _ ->
                    error("A shape's callers submit to it.")
                }
            )
        }
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

    /**
     * Wow's own write path on this store ([options]), on [client]: its appenders' and writers' writers, the log's
     * group and the snapshots' `bulkWrite` in Wow's shape (M0).
     */
    fun shapeOf(options: WowShape.Options, client: MongoClient): WowShape<Writer> = WowShape(
        partitions,
        { Partitioner.partitionOf(ACCOUNT_TYPE.ref(tenant(), it.id), partitions) },
        options,
        { Writer(client, null) },
        { writer, commands -> writer.insertEach(commands) },
        { writer, commands -> writer.writeEach(commands, Update.M0, bigState = false) },
    )

    private fun counted(written: BooleanArray): Flushed = written.count { it }.let { Flushed(it, written.size - it) }

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
        fun insert(commands: List<Aggregates.Command>): Flushed = counted(insertEach(commands))

        /** [commands]' streams, as [insert]: which of them it wrote, in their order. */
        fun insertEach(commands: List<Aggregates.Command>): BooleanArray {
            val documents = commands.map { command ->
                val partition = Partitioner.partitionOf(ACCOUNT_TYPE.ref(tenant(), command.id), partitions)
                logOf(nextTemplate(), command, partition, sequencer.next(partition, counters))
            }
            return try {
                if (session != null) log.insertMany(session, documents) else log.insertMany(documents)
                BooleanArray(commands.size) { true }
            } catch (refused: MongoBulkWriteException) {
                if (refused.writeConcernError != null) throw refused
                val written = refused.writeErrors.minOf { it.index }
                BooleanArray(commands.size) { it < written }
            }
        }

        /** [commands]' snapshots as one unordered `bulkWrite` of [update]'s shape; the ones without a write error. */
        fun write(commands: List<Aggregates.Command>, update: Update, bigState: Boolean): Flushed =
            counted(writeEach(commands, update, bigState))

        /** [commands]' snapshots, as [write]: which of them it wrote, in their order. */
        fun writeEach(commands: List<Aggregates.Command>, update: Update, bigState: Boolean): BooleanArray {
            val first = snapshotTemplates[Math.floorMod(template.get(), snapshotTemplates.size)]
            val models = commands.map { modelOf(first, it, update, bigState) }
            return try {
                if (session != null) {
                    snapshots.bulkWrite(session, models, UNORDERED)
                } else {
                    snapshots.bulkWrite(models, UNORDERED)
                }
                BooleanArray(commands.size) { true }
            } catch (refused: MongoBulkWriteException) {
                if (refused.writeConcernError != null) throw refused
                val failed = refused.writeErrors.map { it.index }.toSet()
                BooleanArray(commands.size) { it !in failed }
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

    private val stats = MongoCounters(admin, database)

    /** MongoDB's cumulative counters (MongoCounters). */
    fun counters(): Map<String, Double> = stats.counters()

    /** Each of Wow's and the clone's collections' writes (MongoCounters). */
    fun tables(): Map<String, Double> = stats.tables()

    /** The snapshot collection's mean write latency over a step, from the counters' growth. */
    fun ratios(growth: Map<String, Double>): Map<String, Double> = stats.ratios(growth)

    /** The server's version and settings, and the log's partitions and sequence block. */
    fun settings(): Map<String, Any?> = stats.settings() + mapOf("partitions" to partitions, "sequenceBlock" to BLOCK)

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
        private const val SEQUENCE = "sequence"
        private const val BLOCKS = "blocks"
        private const val NODE = "node"
        private const val FROM = "from"
        private const val TO = "to"
        private const val COLLECTIONS = "collections"
        private const val NODE_NAME = "raw"
        private const val PREPARE_BATCH = 1_000
        private val UNORDERED = BulkWriteOptions().ordered(false)
    }
}
