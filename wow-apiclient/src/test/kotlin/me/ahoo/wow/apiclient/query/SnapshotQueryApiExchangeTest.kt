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

package me.ahoo.wow.apiclient.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.serialization.toJsonString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

/**
 * The snapshot query APIs against a server stub: each query goes to its route and its response is read into the
 * state type of the API; a missing snapshot is empty (reactive) or `null` (synchronous), while any other failure
 * still fails.
 */
class SnapshotQueryApiExchangeTest {
    private val requestedPaths = mutableListOf<String>()
    private var missing = false
    private var failure: HttpStatus? = null

    private val snapshot = MaterializedSnapshot(
        contextName = "context",
        aggregateName = "state",
        tenantId = "tenant",
        aggregateId = "id-1",
        version = 1,
        eventId = "event-1",
        firstOperator = "operator",
        operator = "operator",
        firstEventTime = 1,
        eventTime = 2,
        state = State("id-1", "value"),
        snapshotTime = 3,
        deleted = false,
    )

    private val factory = HttpServiceProxyFactory.builderFor(
        WebClientAdapter.create(
            WebClient.builder().baseUrl("http://service/state/").exchangeFunction { request ->
                val path = request.url().path.removePrefix("/state/")
                requestedPaths.add(path)
                val status = failure ?: if (missing) HttpStatus.NOT_FOUND else HttpStatus.OK
                Mono.just(
                    ClientResponse.create(status)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(if (status == HttpStatus.OK) responseOf(path) else "")
                        .build()
                )
            }.build()
        )
    ).build()
    private val reactive = factory.createClient(ReactiveStateQueryApi::class.java)
    private val sync = factory.createClient(SyncStateQueryApi::class.java)

    private fun responseOf(path: String): String {
        val state = snapshot.state
        return when (path) {
            "snapshot/single" -> snapshot.toJsonString()
            "snapshot/single/state" -> state.toJsonString()
            "snapshot/list" -> listOf(snapshot).toJsonString()
            "snapshot/list/state" -> listOf(state).toJsonString()
            "snapshot/paged" -> PagedList(1, listOf(snapshot)).toJsonString()
            "snapshot/paged/state" -> PagedList(1, listOf(state)).toJsonString()
            "snapshot/cursor" -> CursorPage(listOf(snapshot), "next").toJsonString()
            "snapshot/cursor/state" -> CursorPage(listOf(state), "next").toJsonString()
            "snapshot/count" -> "1"
            "snapshot/aggregation" -> listOf(mapOf("count" to 1)).toJsonString()
            else -> error("Unexpected path [$path].")
        }
    }

    @Test
    fun `reactive single queries read the snapshot and the state`() {
        reactive.getById("id-1").test().expectNext(snapshot).verifyComplete()
        reactive.getStateById("id-1").test().expectNext(snapshot.state).verifyComplete()
        val query = singleQuery { filter { "aggregateId" eq "id-1" } }
        query.query(reactive).test().expectNext(snapshot).verifyComplete()
        query.queryState(reactive).test().expectNext(snapshot.state).verifyComplete()
        query.dynamicQuery(reactive).test()
            .consumeNextWith { it["aggregateId"].assert().isEqualTo("id-1") }
            .verifyComplete()

        requestedPaths.assert().containsExactly(
            "snapshot/single",
            "snapshot/single/state",
            "snapshot/single",
            "snapshot/single/state",
            "snapshot/single",
        )
    }

    @Test
    fun `a missing snapshot is empty for reactive single queries`() {
        missing = true
        val query = singleQuery { filter { "aggregateId" eq "id-1" } }

        reactive.getById("id-1").test().verifyComplete()
        reactive.getStateById("id-1").test().verifyComplete()
        query.query(reactive).test().verifyComplete()
        query.queryState(reactive).test().verifyComplete()
        query.dynamicQuery(reactive).test().verifyComplete()
    }

    @Test
    fun `synchronous single queries read the snapshot and the state, a missing one is null`() {
        sync.getById("id-1").assert().isEqualTo(snapshot)
        sync.getStateById("id-1").assert().isEqualTo(snapshot.state)
        val query = singleQuery { filter { "aggregateId" eq "id-1" } }
        query.query(sync).assert().isEqualTo(snapshot)
        query.queryState(sync).assert().isEqualTo(snapshot.state)
        query.dynamicQuery(sync)!!["aggregateId"].assert().isEqualTo("id-1")

        missing = true
        sync.getById("id-1").assert().isNull()
        sync.getStateById("id-1").assert().isNull()
        query.query(sync).assert().isNull()
        query.queryState(sync).assert().isNull()
        query.dynamicQuery(sync).assert().isNull()
    }

    @Test
    fun `a failure other than not found still fails`() {
        failure = HttpStatus.INTERNAL_SERVER_ERROR

        reactive.getById("id-1").test().expectError(WebClientResponseException::class.java).verify()
        runCatching { sync.getById("id-1") }.exceptionOrNull()
            .assert().isInstanceOf(WebClientResponseException::class.java)
    }

    @Test
    fun `list, paged, cursor and count queries go to their routes`() {
        val list = listQuery { }
        val paged = pagedQuery { }
        val cursor = cursorQuery { }

        list.query(reactive).test().expectNext(snapshot).verifyComplete()
        list.queryState(reactive).test().expectNext(snapshot.state).verifyComplete()
        list.dynamicQuery(reactive).test().expectNextCount(1).verifyComplete()
        list.query(sync).assert().containsExactly(snapshot)
        list.queryState(sync).assert().containsExactly(snapshot.state)
        list.dynamicQuery(sync).assert().hasSize(1)

        paged.query(reactive).test().expectNext(PagedList(1, listOf(snapshot))).verifyComplete()
        paged.queryState(reactive).test().expectNext(PagedList(1, listOf(snapshot.state))).verifyComplete()
        paged.dynamicQuery(reactive).test().consumeNextWith { it.total.assert().isEqualTo(1) }.verifyComplete()
        paged.query(sync).assert().isEqualTo(PagedList(1, listOf(snapshot)))
        paged.queryState(sync).assert().isEqualTo(PagedList(1, listOf(snapshot.state)))
        paged.dynamicQuery(sync).total.assert().isEqualTo(1)

        reactive.cursor(cursor).test().expectNext(CursorPage(listOf(snapshot), "next")).verifyComplete()
        sync.cursorState(cursor).assert().isEqualTo(CursorPage(listOf(snapshot.state), "next"))

        IdFilter("id-1").count(reactive).test().expectNext(1L).verifyComplete()
        IdFilter("id-1").count(sync).assert().isEqualTo(1L)

        requestedPaths.toSet().assert().containsExactlyInAnyOrder(
            "snapshot/list",
            "snapshot/list/state",
            "snapshot/paged",
            "snapshot/paged/state",
            "snapshot/cursor",
            "snapshot/cursor/state",
            "snapshot/count",
        )
    }

    @Test
    fun `aggregation queries go to their route`() {
        val query = AggregationQuery(metrics = listOf(AggregationMetric.Count("count")))

        query.query(factory.createClient(ReactiveSnapshotAggregationQueryApi::class.java)).test()
            .consumeNextWith { it["count"].assert().isEqualTo(1) }
            .verifyComplete()
        query.query(factory.createClient(SynchronousSnapshotAggregationQueryApi::class.java))
            .single()["count"].assert().isEqualTo(1)
        requestedPaths.assert().containsExactly("snapshot/aggregation", "snapshot/aggregation")
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a legacy condition count goes to the count route`() {
        Condition.id("id-1").count(reactive).test().expectNext(1L).verifyComplete()
        Condition.id("id-1").count(sync).assert().isEqualTo(1L)
    }

    @Test
    fun `a not found from a blocking client is null too`() {
        switchNotFoundToNull<String> {
            throw HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                ByteArray(0),
                null
            )
        }.assert().isNull()
    }

    data class State(val id: String, val value: String)

    interface ReactiveStateQueryApi : ReactiveSnapshotQueryApi<State>

    interface SyncStateQueryApi : SynchronousSnapshotQueryApi<State>
}
