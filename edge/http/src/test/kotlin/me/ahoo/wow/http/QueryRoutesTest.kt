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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.CountResult
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.CursorQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import kotlin.time.toKotlinDuration

/** The snapshot query routes (decisions D127, D129, D130, D150). */
class QueryRoutesTest {
    private val node = QueryNode()

    private fun ids(answer: Answer): List<String> {
        answer.status.assert().isEqualTo(200)
        return answer.body!!.values().map { it["aggregateId"].asString() }
    }

    @Test
    fun `a list should answer the caller's own records, not deleted, in the order asked`() {
        node.save("c-1", items = 3)
        node.save("c-2", owner = "bob")
        node.save("c-3", items = 1)
        node.save("c-4", deleted = true)
        val answer = node.query(
            """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.items","direction":"DESC"}]}"""
        )
        answer.contentType.assert().isEqualTo("application/json")
        ids(answer).assert().containsExactly("c-1", "c-3")
        answer.body!![0]["state"]["items"].asInt().assert().isEqualTo(3)
        answer.body!![0]["ownerId"].asString().assert().isEqualTo("alice")
        // Bob sees only his.
        ids(node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""", user = "bob")).assert().containsExactly("c-2")
        // A query of its own deletion state reads the deleted ones.
        ids(node.query("""{"kind":"list","filter":{"op":"DELETION","state":"DELETED"}}""")).assert()
            .containsExactly("c-4")
    }

    @Test
    fun `another tenant's records should not be visible`() {
        node.save("c-1")
        node.save("c-9", tenant = "other")
        ids(node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""")).assert().containsExactly("c-1")
    }

    @Test
    fun `Wow-Scope should query another scope only for a caller who may act across scopes`() {
        node.save("c-1")
        node.save("c-2", owner = "bob")
        val scope = mapOf(WowHeaders.SCOPE to "owner=\"bob\"")
        ids(node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""", headers = scope + ("X-Admin" to "yes")))
            .assert().containsExactly("c-2")
        node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""", headers = scope)
            .assertQueryProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        // A tenant alone reads every owner of it.
        ids(
            node.query(
                """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"aggregateId","direction":"ASC"}]}""",
                headers = mapOf(WowHeaders.SCOPE to "tenant=\"acme\"", "X-Admin" to "yes"),
            ),
        ).assert().containsExactly("c-1", "c-2")
        node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""", headers = mapOf(WowHeaders.SCOPE to "owner"))
            .assertQueryProblem(400, FrameworkErrors.QUERY_MALFORMED)
    }

    @Test
    fun `a fixed tenant should be queried whatever the caller's, and another named one refused`() {
        val fixed = QueryNode(tenants = TenantPolicy.fixed(mapOf(cartType to "platform")))
        fixed.save("c-1", tenant = "platform")
        fixed.save("c-2")
        ids(fixed.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""")).assert().containsExactly("c-1")
        fixed.query(
            """{"kind":"list","filter":{"op":"MATCH_ALL"}}""",
            headers = mapOf(WowHeaders.SCOPE to "tenant=\"acme\"", "X-Admin" to "yes"),
        ).assertQueryProblem(400, FrameworkErrors.QUERY_MALFORMED)
    }

    @Test
    fun `a cross-tenant query is off by default and only for a caller who may act across scopes`() {
        val list = """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"aggregateId","direction":"ASC"}]}"""
        node.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=*", "X-Admin" to "yes"))
            .assertQueryProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        val across = QueryNode(crossTenant = true)
        across.save("c-1")
        across.save("c-2", tenant = "t2")
        across.save("c-3", tenant = "t3", owner = "bob")
        across.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=*"))
            .assertQueryProblem(403, FrameworkCodes.AUTH_FORBIDDEN)
        ids(across.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=*", "X-Admin" to "yes")))
            .assert().containsExactly("c-1", "c-2", "c-3")
        ids(across.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=(t2 t3)", "X-Admin" to "yes")))
            .assert().containsExactly("c-2", "c-3")
        ids(across.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=*, owner=bob", "X-Admin" to "yes")))
            .assert().containsExactly("c-3")
        across.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=()", "X-Admin" to "yes"))
            .assertQueryProblem(400, FrameworkErrors.QUERY_MALFORMED)
    }

    @Test
    fun `a fixed-tenant type never leaks another tenant to a cross-tenant query`() {
        val fixed = QueryNode(tenants = TenantPolicy.fixed(mapOf(cartType to "platform")), crossTenant = true)
        fixed.save("c-1", tenant = "platform")
        fixed.save("c-2")
        fixed.save("c-3", tenant = "t2")
        val admin = "X-Admin" to "yes"
        val list = """{"kind":"list","filter":{"op":"MATCH_ALL"}}"""
        ids(fixed.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=*", admin))).assert().containsExactly("c-1")
        ids(fixed.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=(t2 platform)", admin)))
            .assert().containsExactly("c-1")
        fixed.query(
            """{"kind":"count","filter":{"op":"MATCH_ALL"}}""",
            headers = mapOf(WowHeaders.SCOPE to "tenant=*", admin)
        )
            .body!!["count"].asLong().assert().isEqualTo(1L)
        fixed.query(list, headers = mapOf(WowHeaders.SCOPE to "tenant=(t2 acme)", admin))
            .assertQueryProblem(400, FrameworkErrors.QUERY_MALFORMED)
    }

    @Test
    fun `paged, single and count should answer their shapes, the count bounded`() {
        (1..5).forEach { node.save("c-$it", items = it) }
        val paged = node.query(
            """{"kind":"paged","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.items","direction":"ASC"}],"pagination":{"index":2,"size":2}}""",
        )
        paged.status.assert().isEqualTo(200)
        paged.body!!["total"].asLong().assert().isEqualTo(5)
        paged.body!!["exact"].asBoolean().assert().isTrue()
        paged.body!!["list"].values().map { it["aggregateId"].asString() }.assert().containsExactly("c-3", "c-4")

        val single = node.query("""{"kind":"single","filter":{"op":"EQ","field":"state.items","value":4}}""")
        single.status.assert().isEqualTo(200)
        single.body!!["aggregateId"].asString().assert().isEqualTo("c-4")
        // A query result, not a statement that an aggregate does not exist (review R4-Q, Q-4).
        node.query("""{"kind":"single","filter":{"op":"EQ","field":"state.items","value":40}}""")
            .assertQueryProblem(404, FrameworkErrors.QUERY_NOT_FOUND)

        val bounded = node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"},"limit":3}""")
        bounded.status.assert().isEqualTo(200)
        bounded.body!!["count"].asLong().assert().isEqualTo(3)
        bounded.body!!["exact"].asBoolean().assert().isFalse()
        val exact = node.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
        exact.body!!["count"].asLong().assert().isEqualTo(5)
        exact.body!!["exact"].asBoolean().assert().isTrue()
    }

    @Test
    fun `a cursor should page through every record once`() {
        (1..5).forEach { node.save("c-$it", items = it) }
        val seen = mutableListOf<String>()
        var cursor: String? = null
        do {
            val after = cursor?.let { ""","cursor":"$it"""" }.orEmpty()
            val page = node.query(
                """{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.items","direction":"DESC"}],"size":2$after}""",
            )
            page.status.assert().isEqualTo(200)
            seen += page.body!!["list"].values().map { it["aggregateId"].asString() }
            cursor = page.body!!["nextCursor"].takeUnless { it.isNull }?.asString()
        } while (cursor != null)
        seen.assert().containsExactly("c-5", "c-4", "c-3", "c-2", "c-1")
        node.query("""{"kind":"cursor","filter":{"op":"MATCH_ALL"},"cursor":"garbage"}""")
            .assertQueryProblem(400, FrameworkErrors.QUERY_INVALID)
            .violations().assert().contains(QueryErrorCodes.INVALID_CURSOR)
    }

    @Test
    fun `an aggregation should answer its rows`() {
        node.save("c-1", items = 2)
        node.save("c-2", items = 3)
        val answer = node.query(
            """{"kind":"aggregate","filter":{"op":"MATCH_ALL"},"metrics":[{"type":"NUMERIC","function":"SUM","expression":{"type":"FIELD","field":"state.items"},"alias":"items"}]}""",
        )
        answer.status.assert().isEqualTo(200)
        answer.body!![0]["items"].asLong().assert().isEqualTo(5)
    }

    @Test
    fun `a list naming no limit should run with the default list size`() {
        val small = QueryNode(defaultListSize = 2)
        (1..3).forEach { small.save("c-$it") }
        ids(small.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""")).assert().hasSize(2)
        ids(small.query("""{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":3}""")).assert().hasSize(3)
        node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":5000}""")
            .assertQueryProblem(400, FrameworkErrors.QUERY_INVALID)
            .violations().assert().contains(QueryErrorCodes.SIZE_OUT_OF_RANGE)
    }

    @Test
    fun `a list should stream as server-sent events`() {
        (1..3).forEach { node.save("c-$it", items = it) }
        val streamed = node.stream(
            """{"kind":"list","filter":{"op":"MATCH_ALL"},"sort":[{"field":"state.items","direction":"ASC"}]}""",
        )
        streamed.status.assert().isEqualTo(200)
        streamed.contentType.assert().startsWith("text/event-stream")
        streamed.events.map { it.event }.assert().containsExactly("row", "row", "row", "end")
        // Each record's id is the cursor token of its position (decision D253), and the stream says it is complete.
        streamed.rows.map { it.id }.assert().doesNotContainNull().doesNotHaveDuplicates()
        streamed.rows.map { it.data["aggregateId"].asString() }.assert().containsExactly("c-1", "c-2", "c-3")
        streamed.end!!["reason"].asString().assert().isEqualTo("complete")
        streamed.end!!["resumable"].asBoolean().assert().isFalse()
        // No match: an empty stream, complete at once.
        node.stream(
            """{"kind":"list","filter":{"op":"EQ","field":"state.items","value":9}}"""
        ).events.map { it.event }.assert().containsExactly("end")
        // Admission fails before the stream starts: a problem.
        node.stream("""{"kind":"list","filter":{"op":"EQ","field":"state.unknown","value":9}}""").let {
            it.status.assert().isEqualTo(400)
            it.contentType.assert().isEqualTo("application/problem+json")
            json.readTree(it.body)["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
        }
    }

    @Test
    fun `a streamed list that fails after its first record should end with an error event`() {
        val failing = QueryNode(snapshots = { FailingList(it) })
        val streamed = failing.stream("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""")
        streamed.status.assert().isEqualTo(200)
        streamed.events.map { it.event }.assert().containsExactly("row", "error")
        // A stream that cannot be resumed carries no positions.
        streamed.events.first().id.assert().isNull()
        streamed.events.last().data["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_FAILED)
        // Not streamed, the same failure is a problem.
        failing.query(
            """{"kind":"list","filter":{"op":"MATCH_ALL"}}"""
        ).assertQueryProblem(500, FrameworkErrors.QUERY_FAILED)
    }

    @Test
    fun `only a list should be answered as an event stream`() {
        node.http.client.post().uri("/shop/cart/snapshot/query")
            .header("X-User", "alice")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .bodyValue("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
            .answer().status.assert().isEqualTo(406)
    }

    @Test
    fun `a body that does not read as a query should be wow-query-malformed with its code`() {
        fun malformed(body: String?, code: String, path: String = "body") {
            val problem = node.query(body).assertQueryProblem(400, FrameworkErrors.QUERY_MALFORMED)
            problem.body!!["violations"][0]["code"].asString().assert().isEqualTo(code)
            problem.body!!["violations"][0]["path"].asString().assert().isEqualTo(path)
        }
        malformed(null, QueryErrorCodes.EMPTY_BODY)
        malformed("{", QueryErrorCodes.INVALID_JSON)
        malformed("[1]", QueryErrorCodes.BODY_NOT_OBJECT)
        malformed("""{"filter":{"op":"MATCH_ALL"}}""", QueryErrorCodes.INVALID_VALUE, "kind")
        malformed("""{"kind":"lists","filter":{"op":"MATCH_ALL"}}""", QueryErrorCodes.UNKNOWN_VALUE, "kind")
        malformed("""{"kind":1,"filter":{"op":"MATCH_ALL"}}""", QueryErrorCodes.UNKNOWN_VALUE, "kind")
        malformed(
            """{"kind":"list","filter":{"op":"MATCH_ALL"},"condition":{}}""",
            QueryErrorCodes.UNKNOWN_PROPERTY,
            "condition"
        )
        malformed("""{"kind":"list"}""", QueryErrorCodes.INVALID_VALUE, "filter")
        malformed("""{"kind":"list","filter":{"op":"NOPE"}}""", QueryErrorCodes.UNKNOWN_TYPE, "filter")
        malformed(
            """{"kind":"paged","filter":{"op":"MATCH_ALL"},"total":"MAYBE"}""",
            QueryErrorCodes.UNKNOWN_VALUE,
            "total"
        )
        malformed("""{"kind":"count","filter":{"op":"MATCH_ALL"},"limit":0}""", QueryErrorCodes.INVALID_REQUEST)
        // The protocol's bounds hold when the query is built: 400 with a query code, never a server error.
        malformed("""{"kind":"list","filter":{"op":"MATCH_ALL"},"limit":0}""", QueryErrorCodes.INVALID_REQUEST)
        malformed(
            """{"kind":"paged","filter":{"op":"MATCH_ALL"},"pagination":{"index":0,"size":10}}""",
            QueryErrorCodes.INVALID_REQUEST,
            "pagination",
        )
        malformed(
            """{"kind":"paged","filter":{"op":"MATCH_ALL"},"pagination":{"index":1,"size":0}}""",
            QueryErrorCodes.INVALID_REQUEST,
            "pagination",
        )
        val sorts = (0..Sort.MAX_FIELDS).joinToString(",") { """{"field":"state.f$it","direction":"ASC"}""" }
        malformed("""{"kind":"single","filter":{"op":"MATCH_ALL"},"sort":[$sorts]}""", QueryErrorCodes.INVALID_REQUEST)
        node.http.client.post().uri("/shop/cart/snapshot/query").header("X-User", "alice")
            .header("Content-Type", "text/plain").bodyValue("{}").answer().status.assert().isEqualTo(415)
    }

    @Test
    fun `a query the model refuses should be wow-query-invalid with each violation's code`() {
        val problem = node.query("""{"kind":"list","filter":{"op":"EQ","field":"state.unknown","value":1}}""")
            .assertQueryProblem(400, FrameworkErrors.QUERY_INVALID)
        problem.body!!["violations"][0]["code"].asString().assert().isEqualTo(QueryErrorCodes.UNKNOWN_FIELD)
        problem.body!!["violations"][0]["path"].asString().assert().isEqualTo("state.unknown")
        problem.body!!["type"].asString().assert().isEqualTo("urn:wow:error:wow.query.invalid")
    }

    @Test
    fun `a query past its budget's timeout should be wow-query-timeout`() {
        val slow = QueryNode(
            storage = InMemoryQueryStorage(delay = Duration.ofMillis(500)),
            entryPolicy = QueryEntryPolicy(
                http = QueryBudget(QueryBudget.HTTP_LABEL, timeout = Duration.ofMillis(50).toKotlinDuration())
            ),
        )
        val timedOut = slow.query(
            """{"kind":"count","filter":{"op":"MATCH_ALL"}}"""
        ).assertQueryProblem(504, FrameworkErrors.QUERY_TIMEOUT)
        // Not worth running again as it is (decision D254 ③): no Retry-After.
        timedOut.headers.getFirst(HttpHeaders.RETRY_AFTER).assert().isNull()
    }

    @Test
    fun `a backend failure should be wow-query-failed without its detail`() {
        val failing = QueryNode(snapshots = { FailingList(it) })
        val problem = failing.query("""{"kind":"count","filter":{"op":"MATCH_ALL"}}""")
            .assertQueryProblem(500, FrameworkErrors.QUERY_FAILED)
        problem.body!!["detail"].asString().assert().isEqualTo("The query failed.")
        failing.query("""{"kind":"paged","filter":{"op":"MATCH_ALL"}}""")
            .assertQueryProblem(500, FrameworkErrors.QUERY_FAILED)
            .body!!["detail"].asString().assert().isEqualTo("The backend is down.")
    }

    @Test
    fun `a request without credentials should be 401`() {
        node.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""", user = null)
            .assertQueryProblem(401, FrameworkCodes.AUTH_UNAUTHENTICATED)
        node.describe(user = null).assertQueryProblem(401, FrameworkCodes.AUTH_UNAUTHENTICATED)
    }

    @Test
    fun `the descriptor should be answered with its version as ETag, and 304 when it is named`() {
        val described = node.describe()
        described.status.assert().isEqualTo(200)
        described.body!!["model"].asString().assert().isEqualTo("SNAPSHOT")
        val version = described.body!!["version"].asString()
        version.assert().startsWith("sha256:")
        described.headers.eTag.assert().isEqualTo("\"$version\"")
        described.body!!["limits"]["defaultListSize"].asInt().assert().isEqualTo(HttpQueries.DEFAULT_LIST_SIZE)
        described.body!!["fields"].values().map { it["path"].asString() }.assert().contains("state.items", "ownerId")
        node.describe(headers = mapOf("If-None-Match" to "\"$version\"")).let {
            it.status.assert().isEqualTo(304)
            it.headers.eTag.assert().isEqualTo("\"$version\"")
        }
        node.describe(headers = mapOf("If-None-Match" to "\"sha256:other\", W/\"$version\"")).status.assert()
            .isEqualTo(304)
        node.describe(headers = mapOf("If-None-Match" to "\"sha256:other\"")).status.assert().isEqualTo(200)
        // A tighter budget is another descriptor.
        QueryNode(entryPolicy = QueryEntryPolicy(http = QueryBudget(QueryBudget.HTTP_LABEL, maxListSize = 10)))
            .describe().headers.eTag.assert().isNotEqualTo("\"$version\"")
    }

    @Test
    fun `a model no backend serves should have no route`() {
        val none = QueryNode(snapshots = { null })
        none.query("""{"kind":"list","filter":{"op":"MATCH_ALL"}}""").status.assert().isEqualTo(404)
        none.describe().status.assert().isEqualTo(404)
        none.describe(model = "events").status.assert().isEqualTo(200)
    }
}

/** The violation codes of a problem. */
fun Answer.violations(): List<String> = body!!["violations"].values().map { it["code"].asString() }

/** Asserts this is the problem of [code] with [status]. */
fun Answer.assertQueryProblem(status: Int, code: String): Answer {
    this.status.assert().isEqualTo(status)
    contentType.assert().isEqualTo("application/problem+json")
    body!!["code"].asString().assert().isEqualTo(code)
    body!!["status"].asInt().assert().isEqualTo(status)
    return this
}

/** A gateway whose list fails after its first record, whose page fails as the backend, and whose count crashes. */
private class FailingList(private val delegate: QueryGateway) : QueryGateway by delegate {
    override suspend fun resumes(query: ListQuery): Boolean = false

    override fun list(query: ListQuery, caller: QueryCaller): Flow<ObjectNode> = flow {
        emit(json.createObjectNode().put("aggregateId", "c-1"))
        throw QueryExecutionException("The backend is down.")
    }

    override suspend fun paged(query: PagedQuery, caller: QueryCaller): PagedList<ObjectNode> =
        throw QueryExecutionException("The backend is down.")

    override suspend fun count(query: CountQuery, caller: QueryCaller): CountResult =
        error("A bug with internal detail.")

    override suspend fun single(query: SingleQuery, caller: QueryCaller): ObjectNode? = delegate.single(query, caller)

    override suspend fun cursor(query: CursorQuery, caller: QueryCaller): CursorPage<ObjectNode> =
        delegate.cursor(query, caller)

    override fun aggregate(query: AggregationQuery, caller: QueryCaller): Flow<ObjectNode> =
        delegate.aggregate(query, caller)
}
