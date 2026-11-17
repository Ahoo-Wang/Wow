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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.query.QueryGatewayFixtures.GatewayBackend
import me.ahoo.wow.query.QueryGatewayFixtures.gateway
import me.ahoo.wow.query.QueryGatewayFixtures.snapshotNode
import me.ahoo.wow.query.admission.AdmittedQuery
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.error.QueryTimeoutException
import me.ahoo.wow.query.policy.QueryEntryPolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * A positive timeout below a millisecond, which a budget and the entry policy accept, bounds the query as a timeout
 * (#6334): the backend runs, and an immediate answer is returned. It is never the zero that would skip the query.
 */
internal class QuerySubmillisecondTimeoutTest {
    @Test
    fun `a positive HTTP budget below a millisecond runs an immediate count`(): Unit = runTest {
        val backend = GatewayBackend(matches = 1)
        val policy = QueryEntryPolicy(http = QueryBudget("HTTP", timeout = 999.microseconds))
        policy.timeout(QueryEntry.HTTP).assert().isEqualTo(999.microseconds)
        val target = gateway(backend, entryPolicy = policy)
        target.count(CountQuery(), QueryCaller(entry = QueryEntry.HTTP)).count.assert().isEqualTo(1L)
        backend.admitted.size.assert().isEqualTo(1)
    }

    @Test
    fun `a positive in-process timeout below a millisecond collects an immediate list`(): Unit = runTest {
        val backend = GatewayBackend(rows = { flowOf(snapshotNode()) })
        val target = gateway(backend, entryPolicy = QueryEntryPolicy(inProcessTimeout = 999.microseconds))
        target.list(ListQuery(limit = 1), QueryCaller.IN_PROCESS).toList().size.assert().isEqualTo(1)
        backend.admitted.size.assert().isEqualTo(1)
    }

    @Test
    fun `a one millisecond timeout runs count and list, as before`(): Unit = runTest {
        val backend = GatewayBackend(matches = 1, rows = { flowOf(snapshotNode()) })
        val target = gateway(backend, entryPolicy = QueryEntryPolicy(inProcessTimeout = 1.milliseconds))
        target.count(CountQuery(), QueryCaller.IN_PROCESS).count.assert().isEqualTo(1L)
        target.list(ListQuery(limit = 1), QueryCaller.IN_PROCESS).toList().size.assert().isEqualTo(1)
        backend.admitted.size.assert().isEqualTo(2)
    }

    @Test
    fun `no timeout runs the same count`(): Unit = runTest {
        val backend = GatewayBackend(matches = 1)
        val target = gateway(backend, entryPolicy = QueryEntryPolicy(inProcessTimeout = null))
        target.count(CountQuery(), QueryCaller.IN_PROCESS).count.assert().isEqualTo(1L)
    }

    @Test
    fun `a timeout below a millisecond still expires, after the backend started, at its millisecond`(): Unit = runTest {
        var entered = false
        val backend = object : GatewayBackend(matches = 1) {
            override suspend fun count(query: AdmittedQuery<CountQuery>): Long {
                entered = true
                delay(DELAYED)
                return super.count(query)
            }
        }
        val target = gateway(backend, entryPolicy = QueryEntryPolicy(inProcessTimeout = 999.microseconds))
        assertThrows<QueryTimeoutException> { target.count(CountQuery(), QueryCaller.IN_PROCESS) }
        entered.assert().isTrue()
        // The coroutine timer counts whole milliseconds: a positive timeout below one waits one.
        testScheduler.currentTime.assert().isEqualTo(1L)
    }

    @Test
    fun `the timeout error names the timeout to the fraction of a millisecond`() {
        QueryTimeoutException(Duration.ofNanos(999_000)).message.assert()
            .isEqualTo("Query exceeded its execution timeout of 0.999 ms.")
        QueryTimeoutException(Duration.ofSeconds(30)).message.assert()
            .isEqualTo("Query exceeded its execution timeout of 30000 ms.")
    }

    private companion object {
        const val DELAYED = 10L
    }
}
