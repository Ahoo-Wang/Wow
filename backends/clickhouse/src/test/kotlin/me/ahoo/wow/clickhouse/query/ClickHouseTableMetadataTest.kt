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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The callers missing the table together share one load. Every exact count below holds because `runTest` runs every
 * caller on its one thread and `runCurrent` runs each until it waits, so all of them wait before the gate opens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClickHouseTableMetadataTest {
    private val table = ClickHouseTable("bi", "orders", "MergeTree", emptyList())
    private val ttl = Duration.ofSeconds(30)

    @Test
    fun `callers missing a cold table together should share one load`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val metadata = ClickHouseTableMetadata(ttl, { 0L }) {
            loads++
            gate.await()
            table
        }
        val callers = List(8) { async { metadata.table() } }
        runCurrent()
        loads.assert().isEqualTo(1)
        gate.complete(Unit)
        callers.awaitAll().forEach { it.assert().isSameAs(table) }
        loads.assert().isEqualTo(1)
    }

    @Test
    fun `callers missing an expired table together should share one load`() = runTest {
        var now = 0L
        var loads = 0
        var gate: CompletableDeferred<Unit>? = null
        val metadata = ClickHouseTableMetadata(ttl, { now }) {
            loads++
            gate?.await()
            table
        }
        metadata.table()
        now = ttl.toNanos()
        gate = CompletableDeferred()
        val callers = List(6) { async { metadata.table() } }
        runCurrent()
        gate.complete(Unit)
        callers.awaitAll()
        loads.assert().isEqualTo(2)
    }

    @Test
    fun `a failed load should fail its own waiters and not a later caller`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val metadata = ClickHouseTableMetadata(ttl, { 0L }) {
            loads++
            gate.await()
            check(loads > 1) { "The first load fails." }
            table
        }
        val callers = List(3) { async { runCatching { metadata.table() } } }
        runCurrent()
        gate.complete(Unit)
        callers.awaitAll().forEach { it.exceptionOrNull()?.message.assert().isEqualTo("The first load fails.") }
        loads.assert().isEqualTo(1)
        metadata.table().assert().isSameAs(table)
        loads.assert().isEqualTo(2)
    }

    @Test
    fun `a waiter should take the load over from a cancelled leader`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val metadata = ClickHouseTableMetadata(ttl, { 0L }) {
            loads++
            gate.await()
            table
        }
        val leader = async { metadata.table() }
        runCurrent()
        val waiter = async { metadata.table() }
        runCurrent()
        leader.cancelAndJoin()
        runCurrent()
        loads.assert().isEqualTo(2)
        gate.complete(Unit)
        waiter.await().assert().isSameAs(table)
    }

    @Test
    fun `a cancelled waiter should load nothing`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val metadata = ClickHouseTableMetadata(ttl, { 0L }) {
            loads++
            gate.await()
            table
        }
        val leader = async { metadata.table() }
        runCurrent()
        async { metadata.table() }.also { runCurrent() }.cancelAndJoin()
        gate.complete(Unit)
        leader.await()
        metadata.table()
        loads.assert().isEqualTo(1)
    }

    @Test
    fun `a caller waiting behind a refresh should take its table without loading`() = runTest {
        var loads = 0
        val gate = CompletableDeferred<Unit>()
        val metadata = ClickHouseTableMetadata(ttl, { 0L }) {
            loads++
            gate.await()
            table
        }
        val refresh = async { metadata.refresh() }
        runCurrent()
        val caller = async { metadata.table() }
        runCurrent()
        gate.complete(Unit)
        refresh.await()
        caller.await().assert().isSameAs(table)
        loads.assert().isEqualTo(1)
    }

    @Test
    fun `a refresh should load even while the table is fresh`() = runTest {
        var loads = 0
        val metadata = ClickHouseTableMetadata(ttl, { 0L }) {
            loads++
            table
        }
        metadata.table()
        metadata.refresh()
        metadata.refresh()
        metadata.current().assert().isSameAs(table)
        loads.assert().isEqualTo(3)
    }
}
