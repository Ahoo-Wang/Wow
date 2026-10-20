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

package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.tck.containers.WowContainers
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * Streamed lists resume (decision D253) on a host over PostgreSQL: after the hold time, after a dropped connection
 * and after the node restarts, from the `id` of the last record read, with no record twice and none missing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresResumableStreamTest {
    private val postgres = WowContainers.newPostgres().also { it.start() }
    private val owner = "stream-" + UUID.randomUUID().toString().take(OWNER_SUFFIX)

    private fun start(vararg properties: Pair<String, Any>) = startHost(
        "wow.log.partitions" to 4,
        "test.postgres.url" to postgres.jdbcUrl,
        "test.postgres.username" to postgres.username,
        "test.postgres.password" to postgres.password,
        // Two records a chunk, so every stream below crosses chunk boundaries.
        "wow.query.stream.chunk-size" to 2,
        *properties,
        sources = listOf(HostTestApp::class.java, PostgresDataSource::class.java),
    )

    /** The accounts of [owner], opened once: `owner-1` to `owner-7`, so the owner sorts them. */
    private val accounts: List<String> = start().use { host ->
        (1..ACCOUNTS).map { index ->
            val opened = host.post("/bank/account/open-account", """{"owner":"$owner-$index"}""")
            opened.status.assert().isEqualTo(200)
            opened.body!!["ref"]["id"].asString()
        }
    }

    private val byOwner = """{"kind":"list","filter":{"op":"IN","field":"aggregateId","values":""" +
        accounts.joinToString(",", "[", "]") { "\"$it\"" } +
        """},"sort":[{"field":"state.owner","direction":"ASC"}]}"""

    // One host at a time: hosts in one JVM share Reactor Netty's global resources, which a host that closes disposes.
    @AfterAll
    fun close() {
        postgres.stop()
    }

    /** A streamed event: its name, its id and its data. */
    private class Event(val event: String?, val id: String?, val data: JsonNode)

    /** The first [take] events of the stream of [body] on [on], resumed after [lastEventId]; then the client leaves. */
    private fun stream(on: Host, lastEventId: String? = null, take: Long = Long.MAX_VALUE, body: String = byOwner) =
        WebClient.create("http://127.0.0.1:${on.publicPort}").post().uri("/bank/account/snapshot/query")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .headers { headers -> lastEventId?.let { headers.set("Last-Event-ID", it) } }
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .take(take)
            .map { Event(it.event(), it.id(), json.readTree(it.data())) }
            .collectList()
            .block(Duration.ofSeconds(30))!!

    private fun owners(events: List<Event>) = events.filter { it.event == "row" }.map { it.data["state"]["owner"].asString() }

    private val expected = (1..ACCOUNTS).map { "$owner-$it" }

    @Test
    fun `a stream resumes after its hold time, one record a connection, to the end`() {
        start("wow.query.stream.max-hold" to "1ms").use { held ->
            val read = mutableListOf<String>()
            var lastEventId: String? = null
            var connections = 0
            do {
                val events = stream(held, lastEventId)
                connections++
                read += owners(events)
                lastEventId = events.lastOrNull { it.event == "row" }?.id ?: lastEventId
                val end = events.last()
                end.event.assert().isEqualTo("end")
            } while (end.data["reason"].asString() == "hold-expired" && connections <= ACCOUNTS * 2)
            read.assert().isEqualTo(expected)
            connections.assert().isGreaterThan(1)
        }
    }

    @Test
    fun `a stream resumes after a dropped connection and after the node restarts`() {
        val first = start().use { host ->
            val all = stream(host)
            owners(all).assert().isEqualTo(expected)
            all.last().data["reason"].asString().assert().isEqualTo("complete")
            // The client leaves after three records: it resumes after the third.
            val first = stream(host, take = 3)
            owners(first).assert().isEqualTo(expected.take(3))
            owners(stream(host, first.last().id)).assert().isEqualTo(expected.drop(3))
            first
        }
        // The node restarts: the same position resumes on the new node.
        start().use { host -> resumedOnRestart(host, first.last().id!!) }
    }

    private fun resumedOnRestart(host: Host, lastEventId: String) {
        owners(stream(host, lastEventId)).assert().isEqualTo(expected.drop(3))
        // A record written since, after the position, is read; no record is read twice.
        val opened = host.post("/bank/account/open-account", """{"owner":"$owner-9"}""")
        opened.status.assert().isEqualTo(200)
        val withNew = byOwner.replace("\"values\":[", "\"values\":[\"${opened.body!!["ref"]["id"].asString()}\",")
        owners(stream(host, lastEventId, body = withNew)).assert().isEqualTo(expected.drop(3) + "$owner-9")
    }

    @Test
    fun `the events model resumes from a position of its own`() = start().use { host ->
        val body = """{"kind":"list","filter":{"op":"IN","field":"aggregateId","values":""" +
            accounts.joinToString(",", "[", "]") { "\"$it\"" } + """}}"""
        val client = WebClient.create("http://127.0.0.1:${host.publicPort}")
        fun events(lastEventId: String? = null) = client.post().uri("/bank/account/events/query")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .headers { headers -> lastEventId?.let { headers.set("Last-Event-ID", it) } }
            .bodyValue(body)
            .retrieve()
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .collectList()
            .block(Duration.ofSeconds(30))!!
        val all = events().filter { it.event() == "row" }
        all.assert().hasSize(ACCOUNTS)
        events(all[3].id()).filter { it.event() == "row" }.map { it.id() }.assert()
            .isEqualTo(all.drop(4).map { it.id() })
        Unit
    }

    private companion object {
        const val ACCOUNTS = 7
        const val OWNER_SUFFIX = 8
        val json: JsonMapper = JsonMapper.builder().build()
    }
}
