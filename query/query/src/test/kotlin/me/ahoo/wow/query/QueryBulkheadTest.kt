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

package me.ahoo.wow.query

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.FrameworkErrors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A store's query bulkhead (review R3.4-2): a query beyond its bound fails at once, `wow.query.busy`. */
class QueryBulkheadTest {
    @Test
    fun `a call beyond the bound should fail busy at once, and pass once a permit is back`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val held = async(Dispatchers.Default) {
            bulkhead.run {
                entered.complete(Unit)
                gate.await()
                "held"
            }
        }
        entered.await()
        bulkhead.running.assert().isEqualTo(1)
        val busy = runCatching { bulkhead.run { "second" } }.exceptionOrNull() as QueryBusyException
        busy.errorCode.assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
        busy.store.assert().isEqualTo("main")
        busy.maxConcurrent.assert().isEqualTo(1)
        busy.errorMsg.assert().doesNotContain("main")
        gate.complete(Unit)
        held.await().assert().isEqualTo("held")
        bulkhead.running.assert().isZero()
        bulkhead.run { "again" }.assert().isEqualTo("again")
    }

    @Test
    fun `a failing call should give its permit back`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1)
        runCatching { bulkhead.run<Unit> { error("storage failed") } }.isFailure.assert().isTrue()
        bulkhead.running.assert().isZero()
    }

    @Test
    fun `a stream should hold its permit until it ends, its collector cancelled or not`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("main", 1)
        val gate = CompletableDeferred<Unit>()
        val rows = bulkhead.run(
            flow {
                emit(1)
                gate.await()
                emit(2)
            },
        )
        val collecting = async(Dispatchers.Default) { rows.toList() }
        while (bulkhead.running == 0) yield()
        runCatching { bulkhead.run(flow { emit(3) }).toList() }.exceptionOrNull()
            .assert().isInstanceOf(QueryBusyException::class.java)
        gate.complete(Unit)
        collecting.await().assert().containsExactly(1, 2)
        bulkhead.running.assert().isZero()
        // A collector that stops after the first row gives the permit back too.
        bulkhead.run(flow { repeat(3) { emit(it) } }).first().assert().isZero()
        bulkhead.running.assert().isZero()
        bulkhead.toString().assert().isEqualTo("QueryBulkhead(store=main, maxConcurrent=1)")
    }

    @Test
    fun `a bound below one should be refused`() {
        assertThrows<IllegalArgumentException> { QueryBulkhead("main", 0) }
    }
}
