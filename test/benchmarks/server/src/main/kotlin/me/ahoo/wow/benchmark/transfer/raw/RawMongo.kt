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
import com.mongodb.client.ClientSession
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.MongoDatabase
import com.mongodb.client.model.BulkWriteOptions
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.InsertManyOptions
import com.mongodb.client.model.ReplaceOneModel
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.UpdateOneModel
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.WriteModel
import org.bson.Document
import org.bson.conversions.Bson
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The raw run on MongoDB: Wow's collections cloned (each index, by `listIndexes`) as `raw_log` and `raw_snapshot`,
 * written with documents of the domain's own commits as templates at Wow's write concern (`majority`): the log's
 * `insertMany`, and the snapshots' unordered `bulkWrite` of version-guarded upserts in one of four [Update] shapes, so
 * each is priced per update as mongod times them (each update of a `bulkWrite` an operation of its own).
 */
class RawMongo(uri: String, databaseName: String) : AutoCloseable {
    /**
     * A snapshot's update, the shapes its store cost is priced in: [M0] Wow's aggregation pipeline `$set` of every field as `$literal`
     * and the stale mark's `$cond`; [M1] a modifier `$set` with no mark in the filter; [M2] M1 with `$setOnInsert` for
     * what never changes; [M3] a `replaceOne` of the document.
     */
    enum class Update { M0, M1, M2, M3 }

    /** A case of this store, the state it writes ([bigState]: about 4 KB, else the domain's), and its flush. */
    class Case(val case: RawCase, val bigState: Boolean, val flush: (ClientSession?, Aggregates) -> Int)

    private val client: MongoClient = MongoClients.create(uri)
    private val database: MongoDatabase = client.getDatabase(databaseName).withWriteConcern(WriteConcern.MAJORITY)
    private val log: MongoCollection<Document> = database.getCollection(RAW_LOG)
    private val snapshots: MongoCollection<Document> = database.getCollection(RAW_SNAPSHOT)
    private lateinit var logTemplates: List<Document>
    private lateinit var snapshotTemplates: List<Document>
    private val sequence = AtomicLong()
    private val template = AtomicInteger()

    /** Reads up to [count] documents of Wow's log and snapshots as templates. */
    fun loadTemplates(count: Int) {
        logTemplates = database.getCollection(LOG).find().sort(Document("_id", -1)).limit(count).toList()
        snapshotTemplates = database.getCollection(SNAPSHOT).find().limit(count).toList()
        check(logTemplates.isNotEmpty() && snapshotTemplates.isNotEmpty()) { "Wow's collections hold no documents." }
    }

    /**
     * The cases: [WOW_TODAY] (today's shape, the calibration: the log's `insertMany` of [logBatch] and their snapshots
     * [snapshotBatch] a `bulkWrite` in [Update.M0]), the log alone, each [Update] at each of [batches] updates a
     * `bulkWrite` with the domain's state and with a 4 KB one, the log's insert and its snapshots' `bulkWrite` in one
     * multi-document transaction against the same calls back to back, and a point read.
     */
    fun cases(logBatch: Int, snapshotBatch: Int, batches: List<Int>): List<Case> = buildList {
        add(
            Case(RawCase(WOW_TODAY, COMMAND, logBatch, "today: the calibration"), false) { _, a ->
                val commands = a.take(logBatch)
                insert(null, commands)
                var from = 0
                for (n in groupsOf(logBatch, snapshotBatch)) {
                    write(null, commands.subList(from, from + n), Update.M0, bigState = false)
                    from += n
                }
                logBatch
            }
        )
        add(
            Case(RawCase("log-only", COMMAND, logBatch, "reference: the log's insertMany alone"), false) { _, a ->
                insert(null, a.take(logBatch))
            }
        )
        for (bigState in listOf(false, true)) {
            for (update in Update.entries) {
                for (n in batches) {
                    val name = "${update.name.lowercase()}-$n-${if (bigState) "state-4k" else "state"}"
                    val prices = if (update == Update.M0) "M0: today" else "B: ${update.name}"
                    add(
                        Case(RawCase(name, "update", n, prices), bigState) { _, a ->
                            write(null, a.take(n), update, bigState)
                        }
                    )
                }
            }
        }
        add(
            Case(RawCase(C_TRANSACTION, COMMAND, logBatch, "C: one multi-document transaction"), false) { s, a ->
                val commands = a.take(logBatch)
                requireNotNull(s).withTransaction<Unit> {
                    insert(s, commands)
                    write(s, commands, Update.M0, bigState = false)
                }
                logBatch
            }
        )
        add(
            Case(RawCase(C_BACK_TO_BACK, COMMAND, logBatch, "C's writes back to back, no transaction"), false) { _, a ->
                val commands = a.take(logBatch)
                insert(null, commands)
                write(null, commands, Update.M0, bigState = false)
            }
        )
        add(
            Case(RawCase("point-read", "read", 1, "reference: a snapshot by its key"), false) { _, a ->
                snapshots.find(Filters.eq("_id", keyOf(tenant(), a.next().id))).first()
                1
            }
        )
    }

    /** Fresh `raw_*` collections with Wow's indexes, and [aggregates] snapshots at version 1, of the 4 KB state when [bigState]. */
    fun prepare(aggregates: Int, bigState: Boolean) {
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
        val first = snapshotTemplates.first()
        (0 until aggregates).chunked(PREPARE_BATCH).forEach { ids ->
            snapshots.insertMany(
                ids.map { id ->
                    Document(first).apply {
                        this["_id"] = keyOf(first.getString(TENANT_ID), "raw-$id")
                        this[AGGREGATE_ID] = "raw-$id"
                        this[VERSION] = 1
                        if (bigState) this[STATE] = BIG_STATE
                    }
                },
                InsertManyOptions().ordered(false),
            )
        }
    }

    /** A session for a case's worker when it writes in a transaction, else null. */
    fun session(case: RawCase): ClientSession? = if (case.name == C_TRANSACTION) client.startSession() else null

    private fun insert(session: ClientSession?, commands: List<Aggregates.Command>): Int {
        val documents = commands.map { command ->
            logOf(logTemplates[Math.floorMod(template.getAndIncrement(), logTemplates.size)], command)
        }
        if (session != null) log.insertMany(session, documents) else log.insertMany(documents)
        return commands.size
    }

    private fun write(session: ClientSession?, commands: List<Aggregates.Command>, update: Update, bigState: Boolean): Int {
        val first = snapshotTemplates[Math.floorMod(template.get(), snapshotTemplates.size)]
        val models = commands.map { modelOf(first, it, update, bigState) }
        if (session != null) snapshots.bulkWrite(session, models, UNORDERED) else snapshots.bulkWrite(models, UNORDERED)
        return commands.size
    }

    private fun tenant(): String = snapshotTemplates.first().getString(TENANT_ID)

    private fun logOf(template: Document, command: Aggregates.Command): Document = Document(template).apply {
        remove("_id")
        this["partition"] = Math.floorMod(command.id.hashCode(), PARTITIONS)
        this["sequence"] = sequence.incrementAndGet()
        this[AGGREGATE_ID] = command.id
        this[VERSION] = command.version
        this["command_id"] = UUID.randomUUID().toString()
        this["request_id"] = UUID.randomUUID().toString()
        this["commit_time"] = System.currentTimeMillis()
    }

    /** [command]'s snapshot save in the shape of [update], guarded by a lower version of no higher state revision. */
    private fun modelOf(
        template: Document,
        command: Aggregates.Command,
        update: Update,
        bigState: Boolean
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
        val full = Filters.and(
            listOf(key, Filters.eq(TENANT_ID, tenant), Filters.eq(AGGREGATE_ID, command.id)) + guards
        )
        return when (update) {
            Update.M0 -> UpdateOneModel(
                full,
                listOf<Bson>(Document("\$set", pipelineSet(fields, command.version))),
                UPSERT
            )
            Update.M1 -> UpdateOneModel(
                Filters.and(full, Filters.exists(STALE_VERSION, false)),
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

    /** Wow's pipeline `$set`: every field as `$literal`, and the stale mark kept only above [version]. */
    private fun pipelineSet(fields: Document, version: Int): Document = Document().apply {
        for ((field, value) in fields.without(TENANT_ID, AGGREGATE_ID)) this[field] = Document("\$literal", value)
        this[STALE_VERSION] = Document(
            "\$cond",
            listOf(Document("\$lte", listOf("\$$STALE_VERSION", version)), "\$\$REMOVE", "\$$STALE_VERSION"),
        )
    }

    private fun Document.without(vararg keys: String): Document = Document(filterKeys { it !in keys })

    private fun keyOf(tenant: String, id: String) = Document("t", tenant).append("a", id)

    /**
     * MongoDB's cumulative counters: the journal's syncs, their time, writes and bytes; the oplog's bytes; the
     * operations; and the snapshot collection's write latency (`latencyStats`, microseconds) and its writes.
     */
    fun counters(): Map<String, Double> {
        val status = database.runCommand(Document("serverStatus", 1))
        val journal = (status["wiredTiger"] as Document?)?.get("log") as Document?
        val ops = status["opcounters"] as Document?
        val oplog = client.getDatabase("local").runCommand(Document("collStats", "oplog.rs"))
        val latency = snapshots.aggregate(listOf(Document("\$collStats", Document("latencyStats", Document()))))
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
            number(latency, "latency")?.let { put("snapshotWriteMicros", it) }
            number(latency, "ops")?.let { put("snapshotWrites", it) }
        }
    }

    /** Each of Wow's collections' inserts, updates and removes (the `top` command): where a calibration's gap is. */
    fun tables(): Map<String, Double> {
        val totals = client.getDatabase("admin").runCommand(Document("top", 1))["totals"] as Document? ?: return emptyMap()
        return buildMap {
            for ((namespace, value) in totals) {
                if (!namespace.contains(".wow_")) continue
                val counts = value as Document
                for (kind in listOf("insert", "update", "remove", "queries", "commands")) {
                    (
                        (counts[kind] as Document?)?.get(
                            "count"
                        ) as Number?
                        )?.let { put("$namespace $kind", it.toDouble()) }
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

    /** The server's version and the settings a ceiling is quoted with: its storage engine, journal and write concern. */
    fun settings(): Map<String, Any?> {
        val build = database.runCommand(Document("buildInfo", 1))
        val status = database.runCommand(Document("serverStatus", 1))
        val options = client.getDatabase("admin").runCommand(Document("getCmdLineOpts", 1))
        val admin = client.getDatabase("admin")
        val replica = runCatching { admin.runCommand(Document("replSetGetConfig", 1)) }.getOrNull()
        val config = replica?.get("config") as Document?
        return mapOf(
            "version" to build.getString("version"),
            "storageEngine" to (status["storageEngine"] as Document?)?.getString("name"),
            "writeConcern" to "majority",
            "journal" to ((options["parsed"] as Document?)?.get("storage") as Document?)?.get("journal")?.toString(),
            "writeConcernMajorityJournalDefault" to config?.get("writeConcernMajorityJournalDefault"),
            "replicaSetMembers" to (config?.get("members") as List<*>?)?.size,
        )
    }

    override fun close() = client.close()

    companion object {
        const val LOG = "wow_log__transfer__account"
        const val SNAPSHOT = "wow_snapshot__transfer__account"
        const val RAW_LOG = "raw_log"
        const val RAW_SNAPSHOT = "raw_snapshot"
        const val WOW_TODAY = "wow-today"
        const val C_TRANSACTION = "c-transaction"
        const val C_BACK_TO_BACK = "c-back-to-back"
        private const val COMMAND = "command"
        private const val TENANT_ID = "tenant_id"
        private const val AGGREGATE_ID = "aggregate_id"
        private const val VERSION = "version"
        private const val STATE = "state"
        private const val STATE_REVISION = "state_revision"
        private const val STALE_VERSION = "stale_version"
        private const val PARTITIONS = 64
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
