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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.spi.sink.Sink
import me.ahoo.wow.spi.state.StateEventRecord
import java.security.MessageDigest

/**
 * The [Sink] of one aggregate type into ClickHouse (design 08 §8, decisions D142, D164): each record one row of the
 * [schema]'s target table ([ClickHouseSinkSchema.target], [ClickHouseRows]), from which ClickHouse's materialized
 * views fill the current state and the events.
 *
 * - **Batches**: [write] inserts its records in order, at most [batchSize] per `INSERT`, and returns once ClickHouse
 *   has acknowledged every one.
 * - **Idempotent by `ref@version`** (decision D45): each insert carries a deduplication token, the record's id for a
 *   batch of one, else a digest of the batch's ids, so a retry of the same batch is dropped by the tables, the views'
 *   included. The runtime's sink subscription writes one record per call, so its retries are dropped by `ref@version`.
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

    override suspend fun write(records: List<StateEventRecord>) {
        if (records.isEmpty()) {
            return
        }
        prepare()
        val table = schema.qualified(schema.target)
        records.chunked(batchSize).forEach { batch ->
            statements.insert(table, ClickHouseRows.bodyOf(batch), tokenOf(batch))
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
