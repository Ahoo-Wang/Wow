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
 * - **DDL**: before its first write, the Sink creates or validates its tables as [ddl] says; a failure fails the write,
 *   which the subscription retries.
 */
@InternalWowApi
class ClickHouseSink internal constructor(
    val schema: ClickHouseSinkSchema,
    private val statements: ClickHouseStatements,
    private val ddl: DdlMode = DdlMode.AUTO,
    private val batchSize: Int = ClickHouseSinkOptions.DEFAULT_BATCH_SIZE,
) : Sink {
    private val preparation = Mutex()

    @Volatile
    private var prepared = ddl == DdlMode.NONE

    init {
        require(batchSize >= 1) { "A batch holds at least one record." }
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

    /** Creates or validates the tables once, as [ddl] says. */
    suspend fun prepare() {
        if (prepared) {
            return
        }
        preparation.withLock {
            if (prepared) {
                return
            }
            if (ddl == DdlMode.AUTO) {
                schema.ddl.forEach { statements.execute(it) }
            }
            validate()
            prepared = true
        }
    }

    private suspend fun validate() {
        val problems = schema.expected.mapNotNull { expected ->
            if (expected.columns == null) {
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
        /** The deduplication token of [batch]: its record's `ref@version`, or a digest of its records' ids. */
        fun tokenOf(batch: List<StateEventRecord>): String {
            batch.singleOrNull()?.let { return it.id }
            val digest = MessageDigest.getInstance("SHA-256")
            batch.forEach { digest.update((it.id + "\n").toByteArray(Charsets.UTF_8)) }
            return "batch:" + digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
