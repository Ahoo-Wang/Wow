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

package me.ahoo.wow.clickhouse.sink

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The [Sink] of one aggregate type into ClickHouse (design 08 §8, decisions D142, D164): each record one row of the
 * [schema]'s target table ([ClickHouseSinkSchema.target], [ClickHouseRows]), from which ClickHouse's materialized
 * views fill the current state and the events.
 *
 * - **Batches** (finding A2-10): one `INSERT` at a time per sink, which carries every write queued while the one before
 *   ran, in order, at most [batchSize] records per `INSERT`; [write] returns once ClickHouse has acknowledged its
 *   records. The sink subscription writes one record per call from many aggregates at once, so a sustained load is
 *   written in a few large inserts instead of one small part per commit, and an idle sink adds no delay.
 * - **Idempotent by `ref@version`** (decision D45): each insert carries a deduplication token, the record's id for a
 *   batch of one, else a digest of the batch's ids, so a retry of the same batch within the tables' deduplication
 *   window is dropped at once, the views' included. Beyond it, and for a batch made up differently, the history tables
 *   are `ReplacingMergeTree`s keyed by `ref@version` ([ClickHouseSinkSchema]): a replayed record is merged away, and a
 *   read with `FINAL` never sees it twice.
 * - **DDL**: before its first write, and again once [recheckEvery] has passed since (finding X-15), the Sink creates
 *   or validates its tables as [ddl] says; a failure fails the write, which the subscription retries. The check
 *   repeated finds what changed since: a table dropped or altered fails the writes until it is as the Sink expects.
 * - **The flat view across versions** (finding X-15): during a rolling upgrade, writers of two state schemas run at
 *   once. The view's comment keeps the fingerprints of the definitions it was created with ([FlatLineage]): a writer
 *   replaces it only when its own definition is newer than every one before it, so an older instance that restarts
 *   leaves a newer one's view as it is, and validates it by existence only.
 * - **Catalog** (decision D241 ②): once its tables are created or validated (at once with `ddl=none`), the Sink
 *   appends the [catalog] entries of its tables to the database's `wow_catalog` ([ClickHouseCatalog]), each with the
 *   table's current uuid; `auto` creates `wow_catalog` when it is missing. A table whose uuid the account cannot read
 *   gets no row, and a failed write is logged and never fails the Sink's write.
 */
@InternalWowApi
class ClickHouseSink internal constructor(
    val schema: ClickHouseSinkSchema,
    private val statements: ClickHouseStatements,
    private val ddl: DdlMode = DdlMode.AUTO,
    private val batchSize: Int = ClickHouseSinkOptions.DEFAULT_BATCH_SIZE,
    private val catalog: List<CatalogEntry> = emptyList(),
    private val recheckEvery: Duration = DEFAULT_RECHECK,
    private val clock: () -> Long = System::currentTimeMillis,
) : Sink {
    private val preparation = Mutex()

    /** When the tables were last created or validated, by [clock]; null before the first time. */
    @Volatile
    private var checkedAt: Long? = null

    /** Whether a newer writer's flat view was found at the last check: logged when it changes. */
    @Volatile
    private var superseded = false

    /** The `wow_catalog` rows appended last; null before the first ([describe]). */
    @Volatile
    private var described: String? = null

    /** With `ddl=none`, whether the one attempt to append the `wow_catalog` rows was made. */
    @Volatile
    private var catalogTried = false

    init {
        require(batchSize >= 1) { "A batch holds at least one record." }
        require(recheckEvery.isPositive()) { "The tables are checked again after a positive interval." }
    }

    /** The writes waiting for an insert, in arrival order. */
    private val queued = ConcurrentLinkedQueue<Queued>()

    /** Held by the write that inserts: one insert at a time, carrying every write queued meanwhile. */
    private val inserting = Mutex()

    /** One write's records, and its completion once an insert carried them. */
    private class Queued(val records: List<StateEventRecord>) {
        val done = CompletableDeferred<Unit>()
    }

    override suspend fun write(records: List<StateEventRecord>) {
        if (records.isEmpty()) {
            return
        }
        prepare()
        val mine = Queued(records)
        queued += mine
        while (!mine.done.isCompleted) {
            inserting.withLock {
                if (!mine.done.isCompleted) {
                    withContext(NonCancellable) { insertQueued() }
                }
            }
        }
        mine.done.await()
    }

    /**
     * Inserts the writes queued (finding A2-10): the sink subscription writes one record per call, from as many
     * aggregates at once as it handles, so the calls that arrive while an insert runs go together in the next one, up
     * to [batchSize] records. Under load an insert carries many records; idle, each goes alone at once. Each write
     * completes with its insert, or fails with it.
     */
    private suspend fun insertQueued() {
        val batch = ArrayList<Queued>()
        var size = 0
        while (true) {
            val next = queued.peek() ?: break
            if (batch.isNotEmpty() && size + next.records.size > batchSize) {
                break
            }
            batch += queued.poll()
            size += next.records.size
        }
        try {
            val table = schema.qualified(schema.target)
            batch.flatMap { it.records }.chunked(batchSize).forEach { records ->
                statements.insert(table, ClickHouseRows.bodyOf(records), tokenOf(records))
            }
            batch.forEach { it.done.complete(Unit) }
        } catch (
            @Suppress("TooGenericExceptionCaught") // Whatever failed the insert fails each write it carried.
            failure: Throwable,
        ) {
            batch.forEach { it.done.completeExceptionally(failure) }
        }
    }

    /**
     * Creates or validates the tables as [ddl] says, before the first write and again once [recheckEvery] has passed
     * since the last time (finding X-15).
     */
    suspend fun prepare() {
        if (ddl == DdlMode.NONE && !catalogTried) {
            preparation.withLock {
                if (!catalogTried) {
                    describe()
                    catalogTried = true
                }
            }
        }
        if (!due()) {
            return
        }
        preparation.withLock {
            if (!due()) {
                return
            }
            val lineage = if (ddl == DdlMode.AUTO) create() else lineage()
            validate(lineage)
            describe()
            checkedAt = clock()
        }
    }

    /**
     * Appends the [catalog] entries of the tables, with their uuids, when they differ from the ones appended last (a
     * view replaced has another uuid); a failure is only logged, and tried again at the next check (decision D241 ②).
     */
    private suspend fun describe() {
        if (catalog.isEmpty()) {
            return
        }
        try {
            val rows = catalog.mapNotNull { entry ->
                val uuid = statements.uuid(schema.database, entry.table)
                if (uuid == null) {
                    logger.warn { "No uuid of ${schema.database}.${entry.table} is readable: wow_catalog gets no row of it." }
                }
                uuid?.let { ClickHouseCatalog.rowOf(schema.database, entry, it) }
            }
            val text = rows.joinToString(separator = "\n", postfix = "\n")
            if (rows.isNotEmpty() && text != described) {
                statements.insert(
                    schema.qualified(ClickHouseCatalog.TABLE),
                    text.toByteArray(Charsets.UTF_8),
                    "catalog:" + ClickHouseCatalog.sha256(text),
                )
                described = text
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") // The catalog never fails the Sink's writes (decision D241 ②).
            failure: Exception,
        ) {
            logger.warn(failure) { "The wow_catalog rows of ${schema.type} in ${schema.database} were not written." }
        }
    }

    private fun due(): Boolean {
        if (ddl == DdlMode.NONE) {
            return false
        }
        val last = checkedAt ?: return true
        return clock() - last >= recheckEvery.inWholeMilliseconds
    }

    /** The lineage of the flat view as its comment keeps it; empty when there is no view. */
    private suspend fun lineage(): FlatLineage = FlatLineage.of(statements.comment(schema.database, schema.stateFlat))

    /**
     * Creates what is missing, and the flat view unless its lineage has this writer's definition already or a newer
     * one ([FlatLineage.supersedes]). Returns the flat view's lineage.
     */
    private suspend fun create(): FlatLineage {
        statements.execute(ClickHouseCatalog.ddl(schema.database))
        schema.ddl.forEach { statements.execute(it) }
        val lineage = lineage()
        val fingerprint = schema.flatFingerprint
        if (lineage.current == fingerprint || lineage.supersedes(fingerprint)) {
            return lineage
        }
        return lineage.then(fingerprint).also { statements.execute(schema.flatView(it)) }
    }

    private suspend fun validate(lineage: FlatLineage) {
        val newer = lineage.supersedes(schema.flatFingerprint)
        if (newer != superseded) {
            superseded = newer
            if (newer) {
                logger.warn {
                    "The flat view ${schema.database}.${schema.stateFlat} is a newer writer's (definitions " +
                        "${lineage.fingerprints}, this one's ${schema.flatFingerprint}): left as it is."
                }
            }
        }
        val problems = schema.expected.mapNotNull { expected ->
            if (expected.columns == null || newer && expected.name == schema.stateFlat) {
                expected.name.takeUnless { statements.exists(schema.database, it) }?.let { "$it does not exist" }
            } else {
                val actual = statements.columns(schema.database, expected.name)
                when {
                    actual.isEmpty() -> "${expected.name} does not exist"
                    actual != expected.columns -> "${expected.name} has the columns $actual, not ${expected.columns}"
                    else -> null
                }
            }
        }
        check(problems.isEmpty()) {
            "The ClickHouse tables of ${schema.type} in ${schema.database} are not the Sink's: ${problems.joinToString(
                "; "
            )}."
        }
    }

    override fun toString(): String = "ClickHouseSink(${schema.database}.${schema.target})"

    @InternalWowApi
    companion object {
        /** How long the Sink writes before it checks its tables again (finding X-15). */
        val DEFAULT_RECHECK: Duration = 1.minutes

        private val logger = KotlinLogging.logger {}

        /** The deduplication token of [batch]: its record's `ref@version`, or a digest of its records' ids. */
        fun tokenOf(batch: List<StateEventRecord>): String {
            batch.singleOrNull()?.let { return it.id }
            val digest = MessageDigest.getInstance("SHA-256")
            batch.forEach { digest.update((it.id + "\n").toByteArray(Charsets.UTF_8)) }
            return "batch:" + digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
