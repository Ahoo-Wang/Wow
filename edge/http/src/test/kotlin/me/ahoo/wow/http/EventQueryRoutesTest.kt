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

package me.ahoo.wow.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.AggregateIdFilter
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryBudget
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntryPolicy
import me.ahoo.wow.query.QueryRequestException
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.events.EventQueryGateway
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.toKotlinDuration

/** The `events` query model (decision D131): one aggregate's streams from the log, never filtered freely. */
class EventQueryRoutesTest {
    private val node = QueryNode()

    /** Three items added to cart c-1 by [user] at 1000, 2000 and 3000 ms. */
    private fun history(id: String = "c-1", user: String = "alice") {
        listOf("apple" to 1000L, "pear" to 2000L, "plum" to 3000L).forEach { (sku, at) ->
            node.http.now = { at }
            node.http.post("/shop/cart/$id/add-item", """{"sku":"$sku"}""", user).status.assert().isEqualTo(200)
        }
    }

    private fun events(body: String, user: String? = "alice", headers: Map<String, String> = emptyMap()) =
        node.query(body, user, QueryRoutes.EVENTS, headers)

    private fun versions(answer: Answer): List<Int> {
        answer.status.assert().isEqualTo(200)
        val rows = if (answer.body!!.isArray) answer.body else answer.body!!["list"]
        return rows!!.values().map { it["version"].asInt() }
    }

    @Test
    fun `a list should answer the aggregate's streams in version order, each with its events`() {
        history()
        val answer = events("""{"kind":"list","filter":{"op":"AGGREGATE_ID","value":"c-1"}}""")
        versions(answer).assert().containsExactly(1, 2, 3)
        val first = answer.body!![0]
        first["aggregateId"].asString().assert().isEqualTo("c-1")
        first["tenantId"].asString().assert().isEqualTo(TENANT)
        first["commitTime"].asLong().assert().isEqualTo(1000)
        first["commandType"].asString().assert().isEqualTo("shop.cart.AddItem")
        first["ownerId"].asString().assert().isEqualTo("alice")
        first["commandId"].asString().assert().isNotBlank()
        first["requestId"].asString().assert().isNotBlank()
        first["events"][0]["name"].asString().assert().isEqualTo("shop.cart.ItemAdded")
        first["events"][0]["revision"].asInt().assert().isEqualTo(1)
        first["events"][0]["body"]["sku"].asString().assert().isEqualTo("apple")
        // The other spellings of the aggregate.
        versions(events("""{"kind":"list","filter":{"op":"ID","value":"c-1"}}""")).assert().hasSize(3)
        versions(events("""{"kind":"list","filter":{"op":"EQ","field":"aggregateId","value":"c-1"}}"""))
            .assert().hasSize(3)
    }

    @Test
    fun `a version or time range should select the streams within it`() {
        history()
        fun range(condition: String) = versions(
            events("""{"kind":"list","filter":{"op":"AND","operands":[{"op":"ID","value":"c-1"},$condition]}}"""),
        )
        range("""{"op":"GTE","field":"version","value":2}""").assert().containsExactly(2, 3)
        range("""{"op":"GT","field":"version","value":2}""").assert().containsExactly(3)
        range("""{"op":"LT","field":"version","value":2}""").assert().containsExactly(1)
        range("""{"op":"LTE","field":"version","value":2}""").assert().containsExactly(1, 2)
        range("""{"op":"EQ","field":"version","value":2}""").assert().containsExactly(2)
        range("""{"op":"BETWEEN","field":"version","lowerBound":2,"upperBound":3}""").assert().containsExactly(2, 3)
        range("""{"op":"GT","field":"version","value":5}""").assert().isEmpty()
        range("""{"op":"GTE","field":"commitTime","value":1500}""").assert().containsExactly(2, 3)
        range("""{"op":"LT","field":"commitTime","value":"1970-01-01T00:00:03Z"}""").assert().containsExactly(1, 2)
        range("""{"op":"MATCH_NONE"}""").assert().isEmpty()
        range("""{"op":"ID","value":"c-2"}""").assert().isEmpty()
        range("""{"op":"TENANT_ID","value":"other"}""").assert().isEmpty()
        range("""{"op":"TENANT_ID","value":"$TENANT"}""").assert().hasSize(3)
    }

    @Test
    fun `paged, cursor, single and count should page by version`() {
        history()
        val id = """{"op":"ID","value":"c-1"}"""
        val paged = events("""{"kind":"paged","filter":$id,"pagination":{"index":2,"size":2}}""")
        versions(paged).assert().containsExactly(3)
        paged.body!!["total"].asLong().assert().isEqualTo(3)
        events("""{"kind":"paged","filter":$id,"pagination":{"index":1,"size":2},"total":"NONE"}""")
            .body!!["total"].isNull.assert().isTrue()

        val first = events("""{"kind":"cursor","filter":$id,"size":2}""")
        versions(first).assert().containsExactly(1, 2)
        val cursor = first.body!!["nextCursor"].asString()
        val last = events("""{"kind":"cursor","filter":$id,"size":2,"cursor":"$cursor"}""")
        versions(last).assert().containsExactly(3)
        last.body!!["nextCursor"].isNull.assert().isTrue()
        events("""{"kind":"cursor","filter":$id,"cursor":"bm9wZQ"}""")
            .assertQueryProblem(400, FrameworkErrors.QUERY_INVALID)
            .violations().assert().containsExactly(QueryErrorCodes.INVALID_CURSOR)

        events("""{"kind":"single","filter":$id}""").body!!["version"].asInt().assert().isEqualTo(1)
        events("""{"kind":"single","filter":{"op":"ID","value":"c-9"}}""").status.assert().isEqualTo(404)

        events("""{"kind":"count","filter":$id}""").body!!.let {
            it["count"].asLong().assert().isEqualTo(3)
            it["exact"].asBoolean().assert().isTrue()
        }
        events("""{"kind":"count","filter":$id,"limit":2}""").body!!.let {
            it["count"].asLong().assert().isEqualTo(2)
            it["exact"].asBoolean().assert().isFalse()
        }
    }

    @Test
    fun `a list should stream as server-sent events`() {
        history()
        val streamed = node.stream(
            """{"kind":"list","filter":{"op":"ID","value":"c-1"},"limit":2}""",
            QueryRoutes.EVENTS
        )
        streamed.status.assert().isEqualTo(200)
        streamed.events.map { it.data["version"].asInt() }.assert().containsExactly(1, 2)
    }

    @Test
    fun `another owner's streams should not be visible`() {
        history()
        versions(events("""{"kind":"list","filter":{"op":"ID","value":"c-1"}}""", user = "bob")).assert().isEmpty()
        versions(
            events(
                """{"kind":"list","filter":{"op":"ID","value":"c-1"}}""",
                user = "bob",
                headers = mapOf(WowHeaders.SCOPE to "owner=\"alice\"", "X-Admin" to "yes"),
            ),
        ).assert().hasSize(3)
    }

    @Test
    fun `admission should refuse free filtering`() {
        fun refused(filter: String, code: String, body: String = """{"kind":"list","filter":$filter}""") =
            events(body).assertQueryProblem(400, FrameworkErrors.QUERY_INVALID).violations().assert()
                .containsExactly(code)
        refused("""{"op":"MATCH_ALL"}""", QueryErrorCodes.STORAGE_UNSUPPORTED)
        refused("""{"op":"EQ","field":"state.items","value":1}""", QueryErrorCodes.UNKNOWN_FIELD)
        refused(
            """{"op":"OR","operands":[{"op":"ID","value":"c-1"},{"op":"ID","value":"c-2"}]}""",
            QueryErrorCodes.STORAGE_UNSUPPORTED,
        )
        refused(
            """{"op":"AND","operands":[{"op":"ID","value":"c-1"},{"op":"NE","field":"version","value":1}]}""",
            QueryErrorCodes.UNSUPPORTED_CAPABILITY
        )
        refused("""{"op":"GT","field":"aggregateId","value":"c"}""", QueryErrorCodes.UNSUPPORTED_CAPABILITY)
        refused("""{"op":"EQ","field":"aggregateId","value":1}""", QueryErrorCodes.VALUE_MISMATCH)
        refused(
            """{"op":"AND","operands":[{"op":"ID","value":"c-1"},{"op":"GT","field":"version","value":"x"}]}""",
            QueryErrorCodes.VALUE_MISMATCH
        )
        refused(
            """{"op":"AND","operands":[{"op":"ID","value":"c-1"},{"op":"GT","field":"commitTime","value":"yesterday"}]}""",
            QueryErrorCodes.VALUE_MISMATCH
        )
        refused(
            "",
            QueryErrorCodes.STORAGE_UNSUPPORTED,
            """{"kind":"list","filter":{"op":"ID","value":"c-1"},"sort":[{"field":"version","direction":"DESC"}]}""",
        )
        refused(
            "",
            QueryErrorCodes.STORAGE_UNSUPPORTED,
            """{"kind":"list","filter":{"op":"ID","value":"c-1"},"projection":{"include":["version"]}}""",
        )
        refused(
            "",
            QueryErrorCodes.STORAGE_UNSUPPORTED,
            """{"kind":"aggregate","filter":{"op":"ID","value":"c-1"},"metrics":[{"type":"COUNT","alias":"count"}]}""",
        )
        // The budget still bounds the sizes.
        refused(
            "",
            QueryErrorCodes.SIZE_OUT_OF_RANGE,
            """{"kind":"list","filter":{"op":"ID","value":"c-1"},"limit":5000}"""
        )
        // A sort by version ascending is the order it answers in anyway.
        versions(
            events(
                """{"kind":"list","filter":{"op":"ID","value":"c-9"},"sort":[{"field":"version","direction":"ASC"}]}"""
            ),
        ).assert().isEmpty()
    }

    @Test
    fun `the descriptor should be answered with its version as ETag`() {
        val described = node.describe(QueryRoutes.EVENTS)
        described.status.assert().isEqualTo(200)
        described.body!!["model"].asString().assert().isEqualTo("events")
        described.body!!["required"][0].asString().assert().isEqualTo("aggregateId")
        described.body!!["limits"]["maxListSize"].asInt().assert().isEqualTo(QueryBudget.HTTP_DEFAULT.maxListSize)
        val version = described.body!!["version"].asString()
        described.headers.eTag.assert().isEqualTo("\"$version\"")
        node.describe(QueryRoutes.EVENTS, headers = mapOf("If-None-Match" to "\"$version\"")).status.assert()
            .isEqualTo(304)
        node.describe(QueryRoutes.EVENTS).body!!["version"].asString().assert().isEqualTo(version)
    }

    @Test
    fun `an in-process caller without a tenant should name it`() {
        history()
        val gateway = EventQueryGateway(cartType, node.http.log)
        val tenanted = AndFilter(listOf(AggregateIdFilter("c-1"), TenantIdFilter(TENANT)))
        assertThrownBy<QuerySchemaValidationException> {
            runBlocking { gateway.list(ListQuery(AggregateIdFilter("c-1")), QueryCaller.IN_PROCESS).toList() }
        }
        runBlocking {
            gateway.list(ListQuery(tenanted), QueryCaller.IN_PROCESS).toList().map { it["version"].asInt() }
                .assert().containsExactly(1, 2, 3)
            // Unbudgeted, a count is exact.
            gateway.count(CountQuery(tenanted), QueryCaller.IN_PROCESS).count.assert().isEqualTo(3)
        }
        gateway.describe(me.ahoo.wow.query.QueryEntry.IN_PROCESS).has("limits").assert().isFalse()
    }

    @Test
    fun `a read past the budget's timeout should be a query timeout`() {
        val slow = EventQueryGateway(
            cartType,
            SlowLog(node.http.log),
            entryPolicy = QueryEntryPolicy(
                http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ofMillis(50).toKotlinDuration())
            ),
        )
        history()
        val caller = QueryCaller(Caller(tenantId = TENANT), entry = me.ahoo.wow.query.QueryEntry.HTTP)
        assertThrownBy<QueryTimeoutException> {
            runBlocking { slow.count(CountQuery(AggregateIdFilter("c-1")), caller) }
        }
        assertThrownBy<QueryTimeoutException> {
            runBlocking { slow.list(ListQuery(AggregateIdFilter("c-1"), limit = 10), caller).toList() }
        }
        assertThrownBy<QueryRequestException> {
            runBlocking { slow.count(CountQuery(AggregateIdFilter("c-1"), limit = 50_000), caller) }
        }
    }
}

/** A log whose reads take a second. */
private class SlowLog(private val delegate: InMemoryEventLog) : EventLog by delegate {
    override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> = flow {
        delay(1000)
        delegate.read(ref, fromVersion, toVersion).collect { emit(it) }
    }
}
