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

package me.ahoo.wow.clickhouse.query

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration

/**
 * A table's metadata, [load]ed (from `system.tables` and `system.columns`) at most once per [ttl].
 *
 * The callers that find no fresh table share one load: the first leads it, the others wait for it and take its table,
 * or its failure. A failed load is not kept, so the next caller leads another. A leader cancelled while it loads
 * leaves the load to one of those waiting. Loads, a [refresh]'s included, run one at a time, and a leader that finds
 * the table fresh once its turn comes (a [refresh] read it meanwhile) takes it without loading.
 */
internal class ClickHouseTableMetadata(
    private val ttl: Duration,
    private val clock: () -> Long = System::nanoTime,
    private val load: suspend () -> ClickHouseTable,
) {
    private val mutex = Mutex()

    @Volatile
    private var cached: Pair<ClickHouseTable, Long>? = null

    /**
     * The load the callers finding no fresh table wait for, completed with its table, its failure, or `null` when its
     * leader was cancelled. Guarded by this object's monitor.
     */
    private var loading: CompletableDeferred<ClickHouseTable?>? = null

    /** The table, read again once [ttl] has passed since the last read. */
    suspend fun table(): ClickHouseTable {
        while (true) {
            fresh()?.let { return it }
            val (pending, leads) = synchronized(this) {
                val running = loading
                if (running != null) {
                    running to false
                } else {
                    CompletableDeferred<ClickHouseTable?>().also { loading = it } to true
                }
            }
            (if (leads) lead(pending) else pending.await())?.let { return it }
        }
    }

    /** The table, read now. */
    suspend fun refresh(): ClickHouseTable = mutex.withLock { read() }

    /** The table as last read; a caller reads it ([table]) before. */
    fun current(): ClickHouseTable = checkNotNull(cached) { "The table is read before it is used." }.first

    private fun fresh(): ClickHouseTable? =
        cached?.takeIf { (_, readAt) -> clock() - readAt < ttl.toNanos() }?.first

    private suspend fun read(): ClickHouseTable = load().also { cached = it to clock() }

    /** Loads the table for [pending]'s callers, and ends [pending] however the load ends. */
    @Suppress("TooGenericExceptionCaught") // Any failure ends the load its waiters share, then is rethrown.
    private suspend fun lead(pending: CompletableDeferred<ClickHouseTable?>): ClickHouseTable {
        try {
            val table = mutex.withLock { fresh() ?: read() }
            settle(pending) { it.complete(table) }
            return table
        } catch (cancelled: CancellationException) {
            settle(pending) { it.complete(null) }
            throw cancelled
        } catch (failure: Throwable) {
            settle(pending) { it.completeExceptionally(failure) }
            throw failure
        }
    }

    /** Ends [pending] by [end], once no caller can join it any more. */
    private inline fun settle(
        pending: CompletableDeferred<ClickHouseTable?>,
        end: (CompletableDeferred<ClickHouseTable?>) -> Unit,
    ) {
        synchronized(this) { loading = null }
        end(pending)
    }
}
