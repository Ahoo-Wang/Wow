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

package me.ahoo.wow.client

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.read.MinVersionNotReachedException
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.api.read.dynamic
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CLASS_KEYWORD
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/** The HTTP query client (decision D259) against a fake peer: its problems, retries and stream resumption. */
class HttpQueryApiTest {
    private val peer = FakePeer()

    @AfterEach
    fun close() {
        peer.close()
    }

    private val queries = WowClient.builder()
        .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
        .service("reader-service")
        .retry(RetryPolicy.of(backoff = 1.milliseconds))
        .build()
        .queries
        .of(Caller.of(tenantId = "t1"))

    private fun record(id: String) = WowJson.mapper.writeValueAsString(
        mapOf(
            "tenantId" to "t1",
            "aggregateId" to id,
            "version" to 1,
            "deleted" to false,
            "firstEventTime" to 1,
            "lastEventTime" to 2,
            "state" to mapOf("items" to 1),
        ),
    )

    private fun row(id: String) = "event: row\nid: p-$id\ndata: ${record(id)}\n\n"

    private val end = "event: end\ndata: {\"reason\":\"hold-expired\",\"resumable\":true}\n\n"
    private val complete = "event: end\ndata: {\"reason\":\"complete\",\"resumable\":false}\n\n"

    private val boxes get() = queries.snapshots(BOX)

    @Test
    fun `a stream should resume after a resumable end and a cut connection, from the last position`(): Unit =
        runBlocking {
            peer.reply(Reply.events(row("b-1"), end), Reply.cut(row("b-2")), Reply.events(row("b-3"), complete))
            val rows = boxes.stream(ListQuery(MatchAllFilter)).toList()
            rows.map { it.row.ref.id }.assert().containsExactly("b-1", "b-2", "b-3")
            rows.map { it.position }.assert().containsExactly("p-b-1", "p-b-2", "p-b-3")
            peer.taken.map { it.headers["Last-Event-ID"] }.assert().containsExactly(null, "p-b-1", "p-b-2")
            WowJson.mapper.readTree(peer.taken.first().body)["kind"].asString().assert().isEqualTo("list")
        }

    @Test
    fun `a stream cut after rows without positions should fail, since it cannot be asked for again`() {
        peer.reply(Reply.events("event: row\ndata: ${record("b-1")}\n\n"))
        caught<WowProblemException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter), null).toList() } }
            .apply {
                code.assert().isEqualTo(FrameworkErrors.PEER_UNAVAILABLE)
                message.assert().contains("cut off")
            }
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a stream that keeps failing without rows should stop after the retry policy's attempts`() {
        // Cut before any row: asked for again from its start, as often as the policy allows.
        peer.reply(Reply.cut(": opening\n"))
        caught<WowProblemException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter)).toList() } }
            .cause.assert().isInstanceOf(IOException::class.java)
        peer.taken.assert().hasSize(RetryPolicy.DEFAULT.maxAttempts)
        peer.taken.clear()
        peer.reply(Reply.events(end))
        caught<IllegalStateException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter), "p-0").toList() } }
            .message.assert().contains("ended: hold-expired")
        peer.taken.assert().hasSize(RetryPolicy.DEFAULT.maxAttempts)
    }

    @Test
    fun `a stream should skip an event it does not know, and fail on an end that says nothing`() {
        peer.reply(Reply.events(row("b-1"), "event: heartbeat\ndata: {}\n\n", "event: end\ndata: {}\n\n"))
        caught<IllegalStateException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter)).toList() } }
            .message.assert().contains("ended: null")
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a stream that ended not resumably, short of complete, should fail`() {
        peer.reply(Reply.events(row("b-1"), "event: end\ndata: {\"reason\":\"hold-expired\",\"resumable\":false}\n\n"))
        caught<IllegalStateException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter)).toList() } }
            .message.assert().contains("ended: hold-expired")
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a retryable error event should resume the stream, and another fail it as the problem it holds`() {
        peer.reply(
            Reply.events(row("b-1"), "event: error\ndata: {\"status\":503,\"code\":\"wow.storage.unavailable\"}\n\n"),
            Reply.events(row("b-2"), complete),
        )
        runBlocking { boxes.stream(ListQuery(MatchAllFilter)).toList() }.map { it.row.ref.id }.assert()
            .containsExactly("b-1", "b-2")
        peer.taken.map { it.headers["Last-Event-ID"] }.assert().containsExactly(null, "p-b-1")
        peer.taken.clear()
        peer.reply(Reply.events(row("b-1"), "event: error\ndata: {\"status\":400,\"code\":\"wow.query.invalid\"}\n\n"))
        val problem = caught<WowProblemException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter)).toList() } }
        problem.code.assert().isEqualTo("wow.query.invalid")
        problem.status.assert().isEqualTo(400)
        peer.taken.assert().hasSize(1)
        Problems.ofMembers("not json").status.assert().isEqualTo(500)
    }

    @Test
    fun `a resumed stream should ask for what is left of its limit, and never answer more`(): Unit = runBlocking {
        peer.reply(
            Reply.events(row("b-1"), row("b-2"), end),
            Reply.events(row("b-3"), row("b-4"), row("b-5"), complete),
        )
        val rows = boxes.stream(ListQuery.of(MatchAllFilter, limit = 3)).toList()
        // A peer that sends more than it was asked for is read no further than the limit.
        rows.map { it.row.ref.id }.assert().containsExactly("b-1", "b-2", "b-3")
        peer.taken.map { WowJson.mapper.readTree(it.body)["limit"].asInt() }.assert().containsExactly(3, 1)

        // Every row of the limit read before the peer ended it: nothing is asked for again.
        peer.taken.clear()
        peer.reply(Reply.events(row("b-1"), row("b-2"), row("b-3"), end))
        boxes.stream(ListQuery.of(MatchAllFilter, limit = 3)).toList().assert().hasSize(3)
        peer.taken.assert().hasSize(1)
    }

    @Test
    fun `a stream whose permit was reclaimed should resume after the retry policy's backoff`(): Unit = runBlocking {
        val reclaimed = "event: end\ndata: {\"reason\":\"permit-reclaimed\",\"resumable\":true}\n\n"
        peer.reply(Reply.events(row("b-1"), reclaimed), Reply.events(reclaimed), Reply.events(row("b-2"), complete))
        val slow = WowClient.builder()
            .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
            .service("reader-service")
            .retry(RetryPolicy.of(backoff = 100.milliseconds))
            .build()
            .queries
            .of(Caller.of(tenantId = "t1"))
            .snapshots(BOX)
        val took = measureTime { slow.stream(ListQuery(MatchAllFilter)).toList().assert().hasSize(2) }
        // 100 ms before the first resume and 200 ms before the second, which follows a resume without a row.
        took.assert().isGreaterThanOrEqualTo(300.milliseconds)
        peer.taken.map { it.headers["Last-Event-ID"] }.assert().containsExactly(null, "p-b-1", "p-b-1")
    }

    @Test
    fun `a query should be run again whenever a retry may get past its problem, and only then`(): Unit = runBlocking {
        peer.reply(
            Reply.problem(429, mapOf("code" to "wow.query.busy"), retryAfter = "0"),
            Reply.json(emptyList<Any>())
        )
        boxes.list(ListQuery(MatchAllFilter)).toList().assert().isEmpty()
        peer.taken.assert().hasSize(2)
        peer.taken.clear()
        // A store that stays down: retried as often as the policy allows, then its problem.
        peer.reply(Reply.problem(503, mapOf("code" to "wow.storage.unavailable"), retryAfter = "0"))
        caught<WowProblemException> { runBlocking { boxes.list(ListQuery(MatchAllFilter)).toList() } }
        peer.taken.assert().hasSize(RetryPolicy.DEFAULT.maxAttempts)
        peer.taken.clear()
        peer.reply(Reply.problem(400, mapOf("code" to "wow.query.invalid")))
        caught<WowProblemException> { runBlocking { boxes.list(ListQuery(MatchAllFilter)).toList() } }
        peer.taken.assert().hasSize(1)
        caught<WowProblemException> { runBlocking { boxes.stream(ListQuery(MatchAllFilter)).toList() } }
        caught<WowProblemException> { runBlocking { boxes.single(SingleQuery(MatchAllFilter)) } }
        caught<WowProblemException> { runBlocking { boxes.state("b-1") } }
    }

    @Test
    fun `only a 404 of no match should be null, any other 404 the problem it is`(): Unit = runBlocking {
        peer.reply(Reply.problem(404, mapOf("code" to "wow.query.not-found")))
        boxes.single(SingleQuery(MatchAllFilter)).assert().isNull()
        peer.reply(Reply.problem(404, mapOf("code" to "wow.aggregate.not-found")))
        boxes.state("b-1").assert().isNull()
        peer.reply(Reply.problem(404, mapOf("code" to "wow.query.dataset-not-found")))
        caught<WowProblemException> { runBlocking { boxes.single(SingleQuery(MatchAllFilter)) } }
            .message.assert().contains("wow.query.dataset-not-found")
        // A route that does not exist answers a 404 without a Wow code.
        peer.reply(Reply.problem(404, emptyMap()))
        caught<WowProblemException> { runBlocking { boxes.single(SingleQuery(MatchAllFilter)) } }
            .code.assert().isEqualTo("http.404")
        peer.reply(Reply.problem(404, mapOf("code" to "wow.query.not-found")))
        caught<WowProblemException> { runBlocking { boxes.state("b-1") } }
            .message.assert().contains("wow.query.not-found")
        peer.reply(Reply.problem(404, emptyMap()))
        caught<WowProblemException> { runBlocking { boxes.state("b-1") } }
    }

    @Test
    fun `a state read of a minimum version should ask for it, and a 409 should be the version not reached`() {
        runBlocking {
            val notReached = mapOf("code" to "wow.query.min-version-not-reached", "version" to 2)
            peer.reply(Reply.problem(409, notReached, retryAfter = "1"))
            val behind = caught<MinVersionNotReachedException> { runBlocking { boxes.state("b-1", 3) } }
            behind.version.assert().isEqualTo(2)
            behind.minVersion.assert().isEqualTo(3)
            behind.ref.assert().isEqualTo(BOX.ref("t1", "b-1"))
            peer.taken.single().path.assert().endsWith("/b-1/state?minVersion=3")
            peer.reply(Reply.problem(404, mapOf("code" to "wow.aggregate.not-found")))
            boxes.state(BOX.ref("t1", "b-2"), 1).assert().isNull()
            // Another 409 is the problem it is.
            peer.reply(Reply.problem(409, mapOf("code" to "wow.aggregate.exists")))
            caught<WowProblemException> { runBlocking { boxes.state("b-3", 1) } }
            caught<IllegalArgumentException> { runBlocking { boxes.state("b-1", 0) } }
            val other = AggregateType("mail", "other").ref("t1", "x")
            caught<IllegalArgumentException> { runBlocking { boxes.state(other, 1) } }
        }
    }

    @Test
    fun `a typed query should read whole records, a projection only through dynamic()`(): Unit = runBlocking {
        val projected = ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("state"))))
        caught<IllegalArgumentException> { runBlocking { boxes.list(projected).toList() } }
        // A projection that only leaves fields out is refused too.
        val excluded = ListQuery(MatchAllFilter, projection = Projection(exclude = listOf(QueryField("state"))))
        caught<IllegalArgumentException> { runBlocking { boxes.list(excluded).toList() } }
        peer.reply(Reply.json(listOf(mapOf("state" to mapOf("items" to 1)))))
        boxes.dynamic<Map<String, Any>>().list(projected).toList().single().keys.assert().containsExactly("state")
        caught<IllegalArgumentException> { runBlocking { boxes.state(AggregateType("mail", "other").ref("t1", "x")) } }
    }

    @Test
    fun `a peer's state class should be found by its contract, once`() {
        val state = StateManifest(Manifest.schemaRef(HttpCommandClientTest.DeliverItems::class.java.name))
        val mail = Manifest(
            contexts = listOf(
                ContextManifest("mail", aggregates = listOf(AggregateManifest("box", state = state))),
                ContextManifest("post", aggregates = listOf(AggregateManifest("box", state = state))),
            ),
        )
        val models = PeerModels.of(listOf(mail))
        caught<IllegalArgumentException> { models.typeOf(HttpCommandClientTest.DeliverItems::class.java) }
            .message.assert().contains("share the state")
        caught<IllegalArgumentException> { models.typeOf(String::class.java) }
            // The code's mistake, not the caller's: a command function that makes it fails its command (PA-9).
            .assert().isInstanceOf(ReadMisuse::class.java)
        PeerModels.of(listOf(Manifest(contexts = listOf(mail.contexts.first()))))
            .typeOf(HttpCommandClientTest.DeliverItems::class.java).assert().isEqualTo(BOX)
    }

    data class BoxDelivered(val items: Int)

    data class BoxLabelled(val label: String)

    @Test
    fun `a peer's events should decode as the classes its contract names, and as trees otherwise`(): Unit =
        runBlocking {
            val delivered = "mail.BoxDelivered"
            val labelled = BoxLabelled::class.java.name
            val contract = Manifest(
                contexts = listOf(
                    ContextManifest(
                        "mail",
                        aggregates = listOf(
                            AggregateManifest(
                                "box",
                                state = StateManifest(Manifest.schemaRef("mail.BoxState")),
                                events = listOf(
                                    // Its class by the schema's x-wow-class.
                                    EventManifest("delivered", schema = Manifest.schemaRef(delivered)),
                                    // Its class by the schema's key, its binary name.
                                    EventManifest("labelled", schema = Manifest.schemaRef(labelled)),
                                    // A class this class path lacks.
                                    EventManifest("lost", schema = Manifest.schemaRef("mail.BoxLost")),
                                ),
                            ),
                        ),
                    ),
                ),
                defs = mapOf(delivered to mapOf(CLASS_KEYWORD to BoxDelivered::class.java.name)),
            )
            val events = WowClient.builder()
                .endpoints(EndpointResolver.of(mapOf("mail" to peer.base), mapOf("mail" to peer.base)))
                .service("reader-service")
                .contracts(listOf(ManifestFragment("test", contract)))
                .build()
                .queries
                .of(Caller.of(tenantId = "t1"))
                .events(BOX)
            val body = { name: String, value: Any -> mapOf("name" to name, "revision" to 1, "body" to value) }
            peer.reply(
                Reply.json(
                    listOf(
                        mapOf(
                            "tenantId" to "t1",
                            "aggregateId" to "b-1",
                            "version" to 1,
                            "commitTime" to 1,
                            "commandId" to "c-1",
                            "requestId" to "r-1",
                            "commandType" to "mail.deliver",
                            "events" to listOf(
                                body("delivered", mapOf("items" to 2)),
                                body("labelled", mapOf("label" to "a")),
                                body("lost", mapOf("why" to "x")),
                                body("unknown", mapOf("n" to 1)),
                            ),
                        ),
                    ),
                ),
            )
            val stream = events.list(ListQuery(MatchAllFilter)).toList().single()
            val bodies = stream.events.map { it.body }
            bodies[0].assert().isEqualTo(BoxDelivered(2))
            bodies[1].assert().isEqualTo(BoxLabelled("a"))
            (bodies[2] as ObjectNode)["why"].asString().assert().isEqualTo("x")
            (bodies[3] as ObjectNode)["n"].asInt().assert().isEqualTo(1)
        }

    @Test
    fun `a peer's event class should be found without a context class loader, and only where the contract names one`() {
        val models = PeerModels(emptyMap(), mapOf(BOX to mapOf("labelled" to BoxLabelled::class.java.name))) { null }
        models.eventClassOf(BOX, "labelled").assert().isEqualTo(BoxLabelled::class.java)
        models.eventClassOf(BOX, "unknown").assert().isNull()
        models.eventClassOf(AggregateType("mail", "other"), "labelled").assert().isNull()
    }

    /** A peer's state as its api generates the view of it (decision D362). */
    @StateView("mail.box")
    data class BoxStateView(val items: Int, val label: String? = null)

    @StateView("mail")
    data class UnnamedStateView(val items: Int)

    @Test
    fun `a generated view should read the peer's state typed, ignoring the fields it does not know`(): Unit =
        runBlocking {
            val views = queries.snapshots<BoxStateView>()
            views.type.assert().isEqualTo(BOX)
            peer.reply(Reply.json(listOf(WowJson.mapper.readValue(record("b-1"), Map::class.java) + ("extra" to 1))))
            val snapshot = views.list(ListQuery(MatchAllFilter)).toList().single()
            snapshot.state.assert().isEqualTo(BoxStateView(items = 1))
            snapshot.ref.assert().isEqualTo(BOX.ref("t1", "b-1"))
            peer.taken.single().path.assert().isEqualTo("/mail/box/snapshot/query")
            // A projection reads no whole view: dynamic() does.
            val projected = ListQuery(MatchAllFilter, projection = Projection(include = listOf(QueryField("state"))))
            caught<IllegalArgumentException> { runBlocking { views.list(projected).toList() } }
            caught<IllegalArgumentException> { queries.snapshots<UnnamedStateView>() }
                .message.assert().contains("not an aggregate's qualified name")
        }
}
