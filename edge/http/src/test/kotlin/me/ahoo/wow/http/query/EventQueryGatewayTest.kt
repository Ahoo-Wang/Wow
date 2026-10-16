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

package me.ahoo.wow.http.query

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
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryError
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Base64

/** The `events` model's gateway in process: its ranges, totals, cursors and records, without the HTTP route. */
class EventQueryGatewayTest {
    private val type = AggregateType("shop", "cart")
    private val log = InMemoryEventLog(partitions = 1)

    init {
        runBlocking {
            (1..3).forEach { version ->
                log.append(
                    EventStreamRecord(
                        ref = type.ref(TENANT, "c-1"),
                        version = version,
                        commandId = "cmd-$version",
                        requestId = "req-$version",
                        commandType = "shop.cart.AddItem",
                        commitTime = 1000L * version,
                        events = listOf(EventRecord("shop.cart.ItemAdded", 1, """{"n":$version}""")),
                        scope = Scope("alice", "s-1"),
                    ),
                    null,
                )
            }
        }
    }

    /** Unbudgeted and without a timeout: the in-process entry. */
    private val untimed = EventQueryGateway(type, log, entryPolicy = QueryEntryPolicy(inProcessTimeout = null))
    private val inProcess = QueryCaller(Caller(tenantId = TENANT), entry = QueryEntry.IN_PROCESS)

    private fun of(vararg conditions: FilterExpression): FilterExpression =
        AndFilter(listOf(AggregateIdFilter("c-1")) + conditions)

    private fun versions(filter: FilterExpression, gateway: EventQueryGateway = untimed): List<Int> =
        runBlocking { gateway.list(ListQuery(filter), inProcess).toList() }.map { it["version"].asInt() }

    private fun code(block: suspend () -> Unit): String =
        (runCatching { runBlocking { block() } }.exceptionOrNull() as QueryError).bindingErrors.single().code

    @Test
    fun `a list without a timeout should stream every stream with its space`() {
        val rows = runBlocking { untimed.list(ListQuery(of()), inProcess).toList() }
        rows.map { it["version"].asInt() }.assert().containsExactly(1, 2, 3)
        rows[0]["spaceId"].asString().assert().isEqualTo("s-1")
        rows[0]["events"][0]["body"]["n"].asInt().assert().isEqualTo(1)
        runBlocking { untimed.single(SingleQuery(of()), inProcess) }!!["version"].asInt().assert().isEqualTo(1)
        runBlocking { untimed.cursor(CursorQuery(of(), size = 5), inProcess) }.nextCursor.assert().isNull()
    }

    @Test
    fun `the principal's space should restrict the streams`() {
        fun visible(space: String) = runBlocking {
            untimed.list(
                ListQuery(of()),
                inProcess.copy(caller = inProcess.caller!!.copy(scope = Scope("alice", space)))
            ).toList()
        }
        visible("s-1").assert().hasSize(3)
        visible("s-2").assert().isEmpty()
    }

    @Test
    fun `the principal's owner should not restrict the streams of an aggregate of its tenant`() {
        fun visible(gateway: EventQueryGateway) = runBlocking {
            gateway.list(
                ListQuery(of()),
                inProcess.copy(caller = inProcess.caller!!.copy(scope = Scope("bob", "s-1")))
            ).toList()
        }
        visible(untimed).assert().isEmpty()
        val tenantWide = EventQueryGateway(
            type,
            log,
            entryPolicy = QueryEntryPolicy(inProcessTimeout = null),
            ownership = Ownership.TENANT,
        )
        visible(tenantWide).assert().hasSize(3)
    }

    @Test
    fun `the totals of a page should follow its mode and the budget`() {
        fun paged(total: TotalMode, gateway: EventQueryGateway = untimed, caller: QueryCaller = inProcess) =
            runBlocking { gateway.paged(PagedQuery(of(), pagination = Pagination(1, 2), total = total), caller) }
        paged(TotalMode.EXACT).let {
            it.total.assert().isEqualTo(3)
            it.exact.assert().isTrue()
        }
        paged(TotalMode.BOUNDED).total.assert().isEqualTo(3)
        val tight = EventQueryGateway(
            type,
            log,
            entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, countLimit = 1)),
        )
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
    fun `ranges should take every number type and stay within their bounds`() {
        val version = QueryField(EventQueryGateway.VERSION)
        val time = QueryField(EventQueryGateway.COMMIT_TIME)
        versions(of(EqualFilter(version, 2L))).assert().containsExactly(2)
        versions(of(EqualFilter(version, BigInteger.TWO))).assert().containsExactly(2)
        versions(of(EqualFilter(version, BigDecimal("2")))).assert().containsExactly(2)
        versions(of(GreaterThanFilter(time, Long.MAX_VALUE))).assert().isEmpty()
        versions(of(LessThanFilter(time, Long.MIN_VALUE))).assert().isEmpty()
        versions(of(LessThanFilter(version, 1))).assert().isEmpty()
        versions(of(LessThanFilter(time, 1000), GreaterThanFilter(time, 1000))).assert().isEmpty()
        versions(of(IdFilter("c-2"))).assert().isEmpty()
        versions(of(TenantIdFilter(TENANT), TenantIdFilter("other"))).assert().isEmpty()
        versions(of(EqualFilter(QueryField(EventQueryGateway.TENANT_ID), TENANT))).assert().hasSize(3)
        code { untimed.list(ListQuery(of(EqualFilter(version, BigDecimal("2.5")))), inProcess).toList() }
            .assert().isEqualTo(QueryErrorCodes.VALUE_MISMATCH)
        code { untimed.list(ListQuery(of(EqualFilter(QueryField("aggregateId"), null))), inProcess).toList() }
            .assert().isEqualTo(QueryErrorCodes.UNSUPPORTED_CAPABILITY)
    }

    @Test
    fun `a cursor should refuse a token it did not write, and end past the last version`() {
        fun token(text: String) = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(text.toByteArray(StandardCharsets.UTF_8))
        listOf("not base64!", token("x:1"), token("v:x"), token("v:0")).forEach { cursor ->
            code { untimed.cursor(CursorQuery(of(), cursor = cursor), inProcess) }
                .assert().isEqualTo(QueryErrorCodes.INVALID_CURSOR)
        }
        runBlocking { untimed.cursor(CursorQuery(of(), cursor = token("v:${Int.MAX_VALUE}")), inProcess) }
            .list.assert().isEmpty()
        // A cursor past the range's last version reads nothing.
        val upToTwo = of(LessThanFilter(QueryField(EventQueryGateway.VERSION), 3))
        runBlocking { untimed.cursor(CursorQuery(upToTwo, cursor = token("v:2")), inProcess) }.list.assert().isEmpty()
        val first = runBlocking { untimed.cursor(CursorQuery(of(), size = 1), inProcess) }
        runBlocking { untimed.cursor(CursorQuery(of(), size = 5, cursor = first.nextCursor), inProcess) }
            .list.map { it["version"].asInt() }.assert().containsExactly(2, 3)
    }

    @Test
    fun `the descriptor should name the limits of a budgeted entry only`() {
        untimed.describe(QueryEntry.HTTP).let {
            it["limits"].has("defaultListSize").assert().isFalse()
            it["model"].asString().assert().isEqualTo(EventQueryGateway.MODEL)
        }
        untimed.describe(QueryEntry.HTTP, 10)["limits"]["defaultListSize"].asInt().assert().isEqualTo(10)
        untimed.describe(QueryEntry.HTTP)["version"].asString().assert()
            .isNotEqualTo(untimed.describe(QueryEntry.HTTP, 10)["version"].asString())
    }

    private companion object {
        const val TENANT = "acme"
    }

    data class ItemAdded(val n: Int, val unit: String)

    class Cart

    @Test
    fun `events should read as the model reads them now when the host gives the model`() {
        val model = aggregate<Cart>("shop", "cart") {
            initial { Cart() }
            evolve<ItemAdded>(revision = 2) { }
            alias<ItemAdded>("shop.cart.ItemPut")
            upgrade<ItemAdded>(from = 1) { payload -> (payload as ObjectNode).put("unit", "piece") }
            retired("shop.cart.ItemGone")
        }
        val stored = InMemoryEventLog(partitions = 1)
        runBlocking {
            stored.append(
                EventStreamRecord(
                    ref = type.ref(TENANT, "c-2"),
                    version = 1,
                    commandId = "cmd-1",
                    requestId = "req-1",
                    commandType = "shop.cart.AddItem",
                    commitTime = 1000L,
                    events = listOf(
                        EventRecord("shop.cart.ItemPut", 1, """{"n":1}"""),
                        EventRecord("shop.cart.ItemGone", 1, "{}"),
                        EventRecord("shop.cart.ItemAdded", 2, """{"n":2,"unit":"box"}"""),
                    ),
                    scope = Scope("alice", "s-1"),
                ),
                null,
            )
        }
        val codec = EventCodec()
        val reading = EventQueryGateway(
            type,
            stored,
            entryPolicy = QueryEntryPolicy(inProcessTimeout = null),
        ) { codec.current(model, it) }
        val events = runBlocking { reading.single(SingleQuery(AggregateIdFilter("c-2")), inProcess) }!!["events"]
        (0 until events.size()).map {
            events[it]
        }.map { "${it["name"].asString()}@${it["revision"].asInt()}:${it["body"]}" }.assert().containsExactly(
            """shop.cart.ItemAdded@2:{"n":1,"unit":"piece"}""",
            """shop.cart.ItemAdded@2:{"n":2,"unit":"box"}""",
        )
        // Without the model, as the log stores them.
        val stored3 = runBlocking {
            EventQueryGateway(type, stored, entryPolicy = QueryEntryPolicy(inProcessTimeout = null))
                .single(SingleQuery(AggregateIdFilter("c-2")), inProcess)
        }!!["events"]
        (0 until stored3.size()).map { stored3[it]["name"].asString() }
            .assert().containsExactly("shop.cart.ItemPut", "shop.cart.ItemGone", "shop.cart.ItemAdded")
    }
}
