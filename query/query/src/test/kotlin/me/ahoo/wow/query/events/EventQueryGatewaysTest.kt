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

package me.ahoo.wow.query.events

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryOwnership
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The events model on the unified engine (decision D235) without a Manifest: system-field queries of one aggregate as
 * before, the caller's scope as a policy, budgets, cursors, the host's reading, and visibility as a policy step.
 */
class EventQueryGatewaysTest {
    private val type = AggregateType("shop", "cart")
    private val streams = (1..3).map { version ->
        EventStreamRecord(
            ref = type.ref(TENANT, "c-1"),
            version = version,
            commandId = "cmd-$version",
            requestId = "req-$version",
            commandType = "shop.cart.AddItem",
            commitTime = 1000L * version,
            events = listOf(EventRecord("shop.cart.ItemAdded", 1, """{"n":$version}""")),
            scope = Scope("alice", "s-1"),
            access = AccessValues.ofStream(emptyMap(), Scope("alice", "s-1")),
        )
    } + EventStreamRecord(
        ref = type.ref(TENANT, "c-2"),
        version = 1,
        commandId = "cmd-c2",
        requestId = "req-c2",
        commandType = "shop.cart.AddItem",
        commitTime = 1000L,
        events = listOf(
            EventRecord("shop.cart.ItemPut", 1, """{"n":1}"""),
            EventRecord("shop.cart.ItemGone", 1, "{}"),
            EventRecord("shop.cart.ItemAdded", 2, """{"n":2,"unit":"box"}"""),
        ),
        scope = Scope("alice", "s-1"),
        access = AccessValues.ofStream(emptyMap(), Scope("alice", "s-1")),
    )
    private val storage = InMemoryEventQueryStorage({ streams })
    private val binding = storage.binding(type)
    private val schema = QueryModelCompiler.of().compile(QuerySchemaContext(type, QueryModel.EVENTS), binding.storage)

    @Suppress("LongParameterList")
    private fun gateway(
        entryPolicy: QueryEntryPolicy = QueryEntryPolicy(inProcessTimeout = null),
        ownership: Ownership = Ownership.OWNER,
        unpinned: UnpinnedEvents = UnpinnedEvents.ALLOW,
        reading: (EventRecord) -> EventRecord? = { it },
    ): QueryGateway = EventQueryGateways.create(
        type,
        binding.backend,
        schema,
        policies = listOf(ScopeQueryPolicy(ownerships = QueryOwnership { ownership })),
        entryPolicy = entryPolicy,
        unpinned = unpinned,
        reading = reading,
    )

    private val untimed = gateway()
    private val inProcess = QueryCaller(Caller(tenantId = TENANT), entry = QueryEntry.IN_PROCESS)

    private fun of(vararg conditions: FilterExpression): FilterExpression =
        AndFilter(listOf(AggregateIdFilter("c-1")) + conditions)

    private fun versions(filter: FilterExpression, gateway: QueryGateway = untimed, caller: QueryCaller = inProcess) =
        runBlocking { gateway.list(ListQuery(filter, limit = 10), caller).toList() }.map { it["version"].asInt() }

    private fun code(block: suspend () -> Unit): String =
        (runCatching { runBlocking { block() } }.exceptionOrNull() as QueryError).bindingErrors.single().code

    @Test
    fun `a list streams every stream of the aggregate in version order`() {
        val rows = runBlocking { untimed.list(ListQuery(of()), inProcess).toList() }
        rows.map { it["version"].asInt() }.assert().containsExactly(1, 2, 3)
        rows[0]["spaceId"].asString().assert().isEqualTo("s-1")
        rows[0]["events"][0]["body"]["n"].asInt().assert().isEqualTo(1)
        runBlocking { untimed.single(SingleQuery(of()), inProcess) }!!["version"].asInt().assert().isEqualTo(1)
        runBlocking { untimed.cursor(CursorQuery(of(), size = 5), inProcess) }.nextCursor.assert().isNull()
    }

    @Test
    fun `a query across the aggregates of the tenant reads them all`() {
        runBlocking { untimed.count(CountQuery(MatchAllFilter), inProcess) }.count.assert().isEqualTo(4)
        versions(MatchAllFilter, caller = QueryCaller(Caller(tenantId = "other"))).assert().isEmpty()
    }

    @Test
    fun `the principal's space restricts the streams`() {
        fun visible(space: String) =
            versions(of(), caller = inProcess.copy(caller = inProcess.caller!!.copyWith(scope = Scope("alice", space))))
        visible("s-1").assert().hasSize(3)
        visible("s-2").assert().isEmpty()
    }

    @Test
    fun `the principal's owner does not restrict the streams of an aggregate of its tenant`() {
        val bob = inProcess.copy(caller = inProcess.caller!!.copyWith(scope = Scope("bob", "s-1")))
        versions(of(), caller = bob).assert().isEmpty()
        versions(of(), gateway(ownership = Ownership.TENANT), bob).assert().hasSize(3)
    }

    @Test
    fun `the totals of a page follow its mode and the budget`() {
        fun paged(total: TotalMode, gateway: QueryGateway = untimed, caller: QueryCaller = inProcess) =
            runBlocking { gateway.paged(PagedQuery(of(), pagination = Pagination(1, 2), total = total), caller) }
        paged(TotalMode.EXACT).let {
            it.total.assert().isEqualTo(3)
            it.exact.assert().isTrue()
        }
        paged(TotalMode.BOUNDED).total.assert().isEqualTo(3)
        val tight = gateway(QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 1)))
        val http = QueryCaller(Caller(tenantId = TENANT), entry = QueryEntry.HTTP)
        paged(TotalMode.BOUNDED, tight, http).let {
            it.total.assert().isEqualTo(1)
            it.exact.assert().isFalse()
            it.list.assert().hasSize(2)
        }
        runBlocking { tight.count(CountQuery(of()), http) }.let {
            it.count.assert().isEqualTo(1)
            it.exact.assert().isFalse()
        }
    }

    @Test
    fun `ranges take every number type and stay within their bounds`() {
        val version = QueryField("version")
        val time = QueryField("commitTime")
        versions(of(EqualFilter(version, 2L))).assert().containsExactly(2)
        versions(of(EqualFilter(version, BigInteger.TWO))).assert().containsExactly(2)
        versions(of(EqualFilter(version, BigDecimal("2")))).assert().containsExactly(2)
        versions(of(GreaterThanFilter(time, Long.MAX_VALUE))).assert().isEmpty()
        versions(of(LessThanFilter(version, 1))).assert().isEmpty()
        versions(of(LessThanFilter(time, 1000), GreaterThanFilter(time, 1000))).assert().isEmpty()
        versions(of(IdFilter("c-2"))).assert().isEmpty()
        versions(of(TenantIdFilter(TENANT), TenantIdFilter("other"))).assert().isEmpty()
        versions(of(EqualFilter(QueryField("tenantId"), TENANT))).assert().hasSize(3)
        code { untimed.list(ListQuery(of(EqualFilter(version, BigDecimal("2.5")))), inProcess).toList() }
            .assert().isEqualTo(QueryErrorCodes.VALUE_MISMATCH)
        code { untimed.list(ListQuery(of(EqualFilter(QueryField("unknown"), 1))), inProcess).toList() }
            .assert().isEqualTo(QueryErrorCodes.UNKNOWN_FIELD)
    }

    @Test
    fun `a cursor refuses a token it did not write and pages by version`() {
        code { untimed.cursor(CursorQuery(of(), cursor = "not base64!"), inProcess) }
            .assert().isEqualTo(QueryErrorCodes.INVALID_CURSOR)
        val first = runBlocking { untimed.cursor(CursorQuery(of(), size = 1), inProcess) }
        runBlocking { untimed.cursor(CursorQuery(of(), size = 5, cursor = first.nextCursor), inProcess) }
            .list.map { it["version"].asInt() }.assert().containsExactly(2, 3)
    }

    @Test
    fun `the descriptor is the unified one, its limits those of the entry`() = runBlocking {
        val descriptor = untimed.describe(QueryEntry.HTTP)
        descriptor.model.assert().isEqualTo(QueryModel.EVENTS)
        descriptor.record.identity.assert().isEqualTo("aggregateId")
        descriptor.fields.map { it.path }.assert().contains(
            "version",
            "commitTime",
            "requestId",
            "operator.id",
            "events"
        )
        descriptor.version.assert().isNotEqualTo(untimed.describe(QueryEntry.HTTP, 10).version)
    }

    @Test
    fun `events read as the host's reading gives them`() {
        // As the host reads them with the model (finding A4-16): renamed, upgraded, a retired event left out.
        val reading = gateway(
            reading = { event ->
                when (event.name) {
                    "shop.cart.ItemGone" -> null
                    "shop.cart.ItemPut" -> EventRecord("shop.cart.ItemAdded", 2, """{"n":1,"unit":"piece"}""")
                    else -> event
                }
            },
        )
        val events = runBlocking { reading.single(SingleQuery(AggregateIdFilter("c-2")), inProcess) }!!["events"]
        (0 until events.size()).map { events[it] }
            .map { "${it["name"].asString()}@${it["revision"].asInt()}:${it["body"]}" }.assert().containsExactly(
                """shop.cart.ItemAdded@2:{"n":1,"unit":"piece"}""",
                """shop.cart.ItemAdded@2:{"n":2,"unit":"box"}""",
            )
        // Without a reading, as the log stores them.
        val stored = runBlocking { untimed.single(SingleQuery(AggregateIdFilter("c-2")), inProcess) }!!["events"]
        (0 until stored.size()).map { stored[it]["name"].asString() }
            .assert().containsExactly("shop.cart.ItemPut", "shop.cart.ItemGone", "shop.cart.ItemAdded")
    }

    @Test
    fun `the descriptor lists AGGREGATE_REQUIRED only where the store refuses an unpinned query`(): Unit = runBlocking {
        gateway(unpinned = UnpinnedEvents.REFUSE).describe(QueryEntry.HTTP).constraints.orEmpty().map { it.type }
            .assert().contains(UnpinnedEventsPolicy.AGGREGATE_REQUIRED)
        gateway().describe(QueryEntry.HTTP).constraints.orEmpty().map { it.type }
            .assert().doesNotContain(UnpinnedEventsPolicy.AGGREGATE_REQUIRED)
    }

    private companion object {
        const val TENANT = "acme"
    }
}
