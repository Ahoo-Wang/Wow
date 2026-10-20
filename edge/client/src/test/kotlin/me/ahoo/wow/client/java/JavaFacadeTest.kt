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

package me.ahoo.wow.client.java

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.EventQueries
import me.ahoo.wow.api.read.Positioned
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.client.caught
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The Java facade's every call delegates to the suspending API (decisions D257, D260). */
class JavaFacadeTest {
    private val type = AggregateType("post", "parcel")
    private val all = MatchAllFilter

    private val rows = mockk<RowQueries<String>> {
        coEvery { single(any()) } returns "one"
        every { list(any()) } returns flowOf("a", "b")
        every { stream(any(), any()) } returns flowOf(Positioned.of("a", "p-1"))
        coEvery { paged(any()) } returns PagedList(1, listOf("a"))
        coEvery { cursor(any()) } returns CursorPage(listOf("a"), null)
        coEvery { count(any()) } returns CountResult(1, true)
        every { aggregate(any(), Map::class.java) } returns flowOf(mapOf("n" to 1))
    }

    @Test
    fun `every query should be read blocking or asynchronously`() {
        val queries = JavaRowQueries(rows)
        queries.single(SingleQuery(all)).assert().isEqualTo("one")
        queries.singleAsync(SingleQuery(all)).join().assert().isEqualTo("one")
        queries.list(ListQuery(all)).assert().containsExactly("a", "b")
        queries.listAsync(ListQuery(all)).join().assert().containsExactly("a", "b")
        queries.stream(ListQuery(all), "p-0").use { it.toList().single().position.assert().isEqualTo("p-1") }
        queries.paged(PagedQuery(all)).total.assert().isEqualTo(1)
        queries.pagedAsync(PagedQuery(all)).join().list.assert().containsExactly("a")
        queries.cursor(CursorQuery(all)).list.assert().containsExactly("a")
        queries.cursorAsync(CursorQuery(all)).join().nextCursor.assert().isNull()
        queries.count(CountQuery(all)).count.assert().isEqualTo(1)
        queries.countAsync(CountQuery(all)).join().exact.assert().isTrue()
        queries.aggregate(
            AggregationQuery(metrics = listOf(me.ahoo.wow.api.query.AggregationMetric.Count("n"))),
            Map::class.java
        ).single()["n"].assert().isEqualTo(1)
        queries.aggregateAsync(
            AggregationQuery(metrics = listOf(me.ahoo.wow.api.query.AggregationMetric.Count("n"))),
            Map::class.java
        ).join().assert().hasSize(1)
    }

    @Test
    fun `trusted, unbounded, events and dataset queries should be the delegate's`() {
        val parcel = type
        val events = mockk<EventQueries> {
            every { type } returns parcel
            every { dynamic(String::class.java) } returns rows
        }
        val unbounded = mockk<Queries>(relaxed = true)
        val trusted = mockk<TrustedQueries> {
            every { events(type) } returns events
            every { unbounded() } returns unbounded
            @OptIn(me.ahoo.wow.api.annotation.ExperimentalWowApi::class)
            every { datasets(type, String::class.java) } returns rows
        }
        val api = JavaQueryApi.of(mockk<QueryApi> { every { trusted() } returns trusted })
        val queries = api.trusted()
        queries.events(type).type.assert().isEqualTo(type)
        queries.events(type).dynamic(String::class.java).list(ListQuery(all)).assert().containsExactly("a", "b")
        @OptIn(me.ahoo.wow.api.annotation.ExperimentalWowApi::class)
        queries.datasets(type, String::class.java).single(SingleQuery(all)).assert().isEqualTo("one")
        queries.unbounded().assert().isNotNull()
    }

    @Test
    fun `a stream's failure should be thrown, and closing it should stop it`() {
        val failing = mockk<RowQueries<String>> {
            every { stream(any(), any()) } returns flow { throw IllegalStateException("down") }
        }
        caught<IllegalStateException> { JavaRowQueries(failing).stream(ListQuery(all)).use { it.toList() } }
        val endless = mockk<RowQueries<String>> {
            every { stream(any(), any()) } returns flow { while (true) emit(Positioned.of("x", null)) }
        }
        JavaRowQueries(endless).stream(ListQuery(all)).use { it.findFirst().get().row.assert().isEqualTo("x") }
        val empty = JavaRowQueries(mockk<RowQueries<String>> { every { stream(any(), any()) } returns flowOf() })
        empty.stream(ListQuery(all)).use { stream ->
            val iterator = stream.iterator()
            iterator.hasNext().assert().isFalse()
            caught<NoSuchElementException> { iterator.next() }
        }
    }

    @Test
    fun `options should be built with Java types`() {
        val options = JavaCommandClient.options()
            .wait(WaitPlan.ACCEPTED)
            .timeout(Duration.ofSeconds(3))
            .requestId("r-1")
            .expectedVersion(2)
            .targetTenant("t2")
            .targetScope(Scope("bob"))
            .build()
        options.plan!!.timeout.assert().isEqualTo(3.seconds)
        options.requestId.assert().isEqualTo("r-1")
        options.expectedVersion.assert().isEqualTo(2)
        options.targetTenant.assert().isEqualTo("t2")
        options.targetScope.assert().isEqualTo(Scope("bob"))
        JavaCommandClient.options().build().assert().isEqualTo(me.ahoo.wow.api.client.SendOptions.DEFAULT)
    }
}
