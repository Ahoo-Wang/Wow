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

package me.ahoo.wow.kernel

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.read.DatasetName
import me.ahoo.wow.api.read.EventQueries
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.SnapshotQueries
import me.ahoo.wow.api.read.SubscriptionQueries
import me.ahoo.wow.api.read.TenantQueryApi
import me.ahoo.wow.api.read.TrustedQueries
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.spi.id.IdGenerators
import org.junit.jupiter.api.Test

/** Decision D259: every handler reads through its context's queries, in the tenant of the commit it handles. */
@OptIn(ExperimentalWowApi::class)
class ContextQueriesTest {
    private val order = AggregateType("shop", "order")
    private val event = StateEvent(
        order.ref("acme", "o-1"),
        3,
        "c-3",
        "r-3",
        "shop.order.Place",
        1L,
        listOf("placed"),
        "state",
        scope = Scope("alice"),
    )

    private val snapshots = mockk<SnapshotQueries<Any>>()
    private val typed = mockk<SnapshotQueries<String>>()
    private val events = mockk<EventQueries>()
    private val rows = mockk<RowQueries<Map<*, *>>>()
    private val sales = DatasetName.of("shop", "sales")
    private val trusted = mockk<TrustedQueries>()
    private val caller = slot<Caller>()
    private val tenantQueries = mockk<Queries> {
        every { snapshots(order) } returns snapshots
        every { snapshots(String::class.java) } returns typed
        every { events(order) } returns events
        every { datasets(sales, Map::class.java) } returns rows
    }
    private val api = mockk<QueryApi> {
        every { of(capture(caller)) } returns tenantQueries
        every { trusted() } returns trusted
    }

    @Test
    fun `every handler's context should read in the tenant of its commit`() {
        val queries = ContextQueries.of(api)
        listOf(FunctionKind.PROJECT, FunctionKind.REACT, FunctionKind.SAGA).forEach { kind ->
            val context = contextOf(kind, "shop.processor.on", event, queries = queries)
            val read = context.queries
            read.tenantId.assert().isEqualTo("acme")
            read.snapshots(order).assert().isSameAs(snapshots)
            read.snapshots(String::class.java).assert().isSameAs(typed)
            read.events(order).assert().isSameAs(events)
            read.datasets(sales, Map::class.java).assert().isSameAs(rows)
            read.trusted().assert().isSameAs(trusted)
        }
        caller.captured.assert().isEqualTo(Caller.of(tenantId = "acme"))
    }

    @Test
    fun `a receipt's handler should read in the tenant of the commit that derived its command`() {
        ReceiptSagaContext(
            ExecutionId("shop.saga.onPlaced", order.ref("acme", "o-1"), 3),
            "cmd-1",
            Causation("root", "cause"),
            null,
            Scope.NONE,
            IdGenerators.DEFAULT,
            contextQueries = ContextQueries.of(api),
        ).queries.tenantId.assert().isEqualTo("acme")
    }

    @Test
    fun `a handler's queries should be those a node's API pins to the tenant`() {
        val pinned = mockk<SubscriptionQueries>()
        val node = mockk<TenantQueryApi> { every { tenant("acme") } returns pinned }
        ContextQueries.of(node).of("acme").assert().isSameAs(pinned)
    }

    @Test
    fun `a context without queries should refuse a read`() {
        val context = contextOf(FunctionKind.SAGA, "shop.processor.on", event)
        assertThrownBy<UnsupportedOperationException> { context.queries }
        assertThrownBy<UnsupportedOperationException> { ExecutionProjectContext(context.executionId).queries }
    }
}
