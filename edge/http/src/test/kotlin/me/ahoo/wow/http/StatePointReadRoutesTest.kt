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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.client.wire.WowHeaders
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.http.read.StateHandler
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.query.QueryAudit
import me.ahoo.wow.query.QueryBulkhead
import me.ahoo.wow.query.QueryObserver
import me.ahoo.wow.query.engine.ReadFacade
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.state.KernelStateReads
import me.ahoo.wow.runtime.state.ReplayGate
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The hardening of the state point reads (decision D254, review R4-Q): replays bounded and gated, the caller checked
 * before any replay (Q-1), whatever the caller cannot see not found (Q-5), the ETag of what is read (Q-6), every read
 * observed (Q-7), parameter errors as query errors (Q-4).
 */
class StatePointReadRoutesTest {
    private val ref = AggregateType(CONTEXT, "cart").ref(TENANT, "c-1")

    /** [count] commits of alice's cart appended to the log as the kernel would, one item each, a second apart. */
    private fun HttpNode.history(count: Int) = runBlocking {
        val codec = EventCodec()
        (1..count).forEach { version ->
            log.append(
                EventStreamRecord(
                    ref = ref,
                    version = version,
                    commandId = "cmd-$version",
                    requestId = "req-$version",
                    commandType = "shop.cart.AddItem",
                    commitTime = 1000L * version,
                    events = listOf(codec.encode(cartModel, ItemAdded("apple", 1))),
                    scope = Scope("alice", null),
                ),
            )
        }
    }

    /**
     * A snapshot of alice's cart at [version], as the snapshot subscription would write it, or rewritten in place
     * with [note] as a rebuild of its state revision would (decision D172).
     */
    private fun HttpNode.snapshot(version: Int, note: String? = null) = runBlocking {
        val state = Cart().apply {
            items = version
            this.note = note.orEmpty()
        }
        val snapshot = EventCodec().snapshotOf(cartModel, ref, state, version, Scope("alice"), 1000L, 1000L * version)
        if (note == null) snapshots.save(snapshot) else snapshots.rewrite(snapshot)
    }

    /** [block]'s answer and the streams of the log it read. */
    private fun HttpNode.reading(block: HttpNode.() -> Answer): Pair<Answer, Int> {
        streamsRead.set(0)
        val answer = block()
        return answer to streamsRead.get()
    }

    @Test
    fun `a long history is replayed within the bound, refused past it, and never read whole`() {
        val node = HttpNode()
        node.history(LONG)
        node.snapshot(10)
        // From the snapshot, the state at the last version is more than the bound away: refused before any read.
        val (tooFar, unread) = node.reading { get("/shop/cart/c-1/state?version=$LONG") }
        tooFar.assertInvalid(QueryErrorCodes.REPLAY_TOO_LONG, "version")
        tooFar.body!!["detail"].asString().assert().contains("$MAX_REPLAY")
        unread.assert().isZero()
        val (traced, untraced) = node.reading { get("/shop/cart/c-1/state/trace?from=$LONG") }
        traced.assertInvalid(QueryErrorCodes.REPLAY_TOO_LONG, "from")
        untraced.assert().isZero()
        // As of a time, the log is folded as it is read and refused at the bound: the snapshot's own stream, the
        // bound's streams and the one past it, never the rest.
        val (late, read) = node.reading { get("/shop/cart/c-1/state?at=1970-12-31T00:00:00Z") }
        late.assertInvalid(QueryErrorCodes.REPLAY_TOO_LONG, "at")
        read.assert().isEqualTo(MAX_REPLAY + 2)
        // Within the bound, the state is replayed from the snapshot.
        val (within, replayed) = node.reading { get("/shop/cart/c-1/state?version=5000") }
        within.status.assert().isEqualTo(200)
        within.body!!["state"]["items"].asInt().assert().isEqualTo(5000)
        replayed.assert().isEqualTo(5000 - 10 + 1)
        // A caller who may not see the cart reads nothing of its log.
        val (refused, none) = node.reading { get("/shop/cart/c-1/state?version=5000", user = "bob") }
        refused.assertNotFound()
        none.assert().isZero()
    }

    @Test
    fun `a minimum version reads the snapshot caught up with the log, and one not reached is 409 with the version`() {
        val node = HttpNode(minVersionWait = 50.milliseconds)
        node.history(3)
        node.snapshot(2)
        node.get("/shop/cart/c-1/state").body!!["version"].asInt().assert().isEqualTo(2)
        // Read your writes: the lagging snapshot is caught up with the log.
        val caughtUp = node.get("/shop/cart/c-1/state?minVersion=3")
        caughtUp.status.assert().isEqualTo(200)
        caughtUp.body!!["version"].asInt().assert().isEqualTo(3)
        caughtUp.body!!["state"]["items"].asInt().assert().isEqualTo(3)
        node.get("/shop/cart/c-1/state?minVersion=2").body!!["version"].asInt().assert().isEqualTo(2)
        // Not committed within the wait: 409, its current version, and a retry later.
        val behind = node.get("/shop/cart/c-1/state?minVersion=4")
        behind.status.assert().isEqualTo(409)
        behind.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_MIN_VERSION_NOT_REACHED)
        behind.body!!["version"].asInt().assert().isEqualTo(3)
        behind.headers.getFirst(HttpHeaders.RETRY_AFTER).assert().isEqualTo("1")
        // A caller who may not see it, and an aggregate with no commit, are not found as ever (Q-5).
        node.get("/shop/cart/c-1/state?minVersion=4", user = "bob").assertNotFound()
        node.get("/shop/cart/none/state?minVersion=1").assertNotFound()
        // It reads the latest state: not with a version or a time, and from 1.
        node.get("/shop/cart/c-1/state?minVersion=3&version=1")
            .assertInvalid(QueryErrorCodes.INVALID_REQUEST, "minVersion")
        node.get("/shop/cart/c-1/state?minVersion=3&at=1970-01-01T00:00:02Z")
            .assertInvalid(QueryErrorCodes.INVALID_REQUEST, "minVersion")
        node.get("/shop/cart/c-1/state?minVersion=0").status.assert().isEqualTo(400)
    }

    @Test
    fun `a caller who may not see the state never makes its log be read`() {
        val node = HttpNode()
        node.history(3)
        node.snapshot(3)
        listOf(
            "/shop/cart/c-1/state",
            "/shop/cart/c-1/state?version=1",
            "/shop/cart/c-1/state?at=1970-01-01T00:00:02Z",
            "/shop/cart/c-1/state/trace",
            "/shop/cart/c-1/state/trace?from=2",
        ).forEach { path ->
            val (answer, read) = node.reading { get(path, user = "bob") }
            answer.assertNotFound()
            read.assert().describedAs(path).isZero()
            // A reader in a space the cart is not in is told the same.
            val (inSpace, readInSpace) = node.reading {
                get(path, headers = mapOf("X-Spaces" to "home", WowHeaders.SPACE to "home"))
            }
            inSpace.assertNotFound()
            readInSpace.assert().describedAs(path).isZero()
        }
        // A cart that does not exist is answered alike, and no log is replayed for a version of it.
        val (missing, read) = node.reading { get("/shop/cart/c-2/state?version=1") }
        missing.assertNotFound()
        read.assert().isZero()
    }

    @Test
    fun `the bound applies before the first snapshot too`() {
        val node = HttpNode(maxReplay = 2)
        node.history(3)
        node.get("/shop/cart/c-1/state").assertInvalid(QueryErrorCodes.REPLAY_TOO_LONG, "")
        node.get("/shop/cart/c-1/state?version=1").assertInvalid(QueryErrorCodes.REPLAY_TOO_LONG, "version")
        node.snapshot(2)
        node.get("/shop/cart/c-1/state?version=1").status.assert().isEqualTo(200)
        node.get("/shop/cart/c-1/state/trace?from=2").body!!["commits"].size().assert().isEqualTo(2)
    }

    @Test
    fun `a replay the bulkhead has no permit for is busy`(): Unit = runBlocking {
        val bulkhead = QueryBulkhead("log", maxConcurrent = 1, maxWait = ZERO, maxQueued = 0)
        val held = CompletableDeferred<Unit>()
        val gated = CopyOnWriteArrayList<AggregateRef>()
        val gate = object : ReplayGate {
            override suspend fun <T> replay(ref: AggregateRef, read: suspend () -> T): T = bulkhead.run {
                gated += ref
                held.await()
                read()
            }
        }
        val node = HttpNode(replayGate = gate)
        node.history(3)
        node.snapshot(3)
        val first = async(Dispatchers.IO) { node.get("/shop/cart/c-1/state?version=1") }
        withTimeout(10.seconds) {
            while (bulkhead.running == 0) {
                yield()
            }
        }
        val busy = node.get("/shop/cart/c-1/state/trace?from=2")
        busy.status.assert().isEqualTo(429)
        busy.body!!["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_BUSY)
        busy.headers.getFirst(HttpHeaders.RETRY_AFTER).assert().isNotNull()
        node.get("/shop/cart/c-1/state?at=1970-01-01T00:00:02Z").status.assert().isEqualTo(429)
        // The latest state is the snapshot: it needs no permit.
        node.get("/shop/cart/c-1/state").status.assert().isEqualTo(200)
        held.complete(Unit)
        first.await().status.assert().isEqualTo(200)
        gated.assert().containsExactly(ref)
    }

    @Test
    fun `a parameter the request gets wrong is an invalid query naming it`() {
        val node = HttpNode()
        node.history(2)
        node.get("/shop/cart/c-1/state?version=0").assertInvalid(QueryErrorCodes.INVALID_VALUE, "version")
        node.get("/shop/cart/c-1/state?at=yesterday").assertInvalid(QueryErrorCodes.INVALID_VALUE, "at")
        node.get("/shop/cart/c-1/state?version=1&at=1970-01-01T00:00:01Z")
            .assertInvalid(QueryErrorCodes.INVALID_REQUEST, "at")
        node.get("/shop/cart/c-1/state/trace?limit=${StateHandler.MAX_TRACE_LIMIT + 1}")
            .assertInvalid(QueryErrorCodes.INVALID_VALUE, "limit")
        node.get("/shop/cart/c-1/state", headers = mapOf(WowHeaders.SCOPE to "owner=\"alice"))
            .assertCode(400, FrameworkErrors.QUERY_MALFORMED)
        // A state read never crosses tenants (decision D254 ⑦).
        node.get("/shop/cart/c-1/state", headers = mapOf(WowHeaders.SCOPE to "tenant=*", "X-Admin" to "true"))
            .assertCode(400, FrameworkErrors.QUERY_MALFORMED)
    }

    @Test
    fun `the ETag changes with what is read, not only with the version`() {
        val node = HttpNode()
        node.history(2)
        node.snapshot(2)
        val before = node.get("/shop/cart/c-1/state").headers.eTag!!
        // A state rebuilt in place at the same version (decision D172): the version stays, the body does not.
        node.snapshot(2, note = "rebuilt")
        val after = node.get("/shop/cart/c-1/state", headers = mapOf("If-None-Match" to before))
        after.status.assert().isEqualTo(200)
        after.headers.eTag.assert().isNotEqualTo(before)
        after.headers.eTag!!.startsWith("\"2-").assert().isTrue()
    }

    @Test
    fun `every state read is observed and audited, a refusal's reason only in the audit`() {
        val audits = CopyOnWriteArrayList<QueryAudit>()
        val ended = CopyOnWriteArrayList<String>()
        val observer = object : QueryObserver {
            override val audits: Boolean = true

            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                ended += "complete:$queryType"
            }

            override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
                ended += "error:$queryType"
            }

            override fun onAudit(audit: QueryAudit) {
                audits += audit
            }
        }
        val node = HttpNode(queries = { HttpQueries(reads = ReadFacade({ null }, observer = observer)) })
        node.history(3)
        node.snapshot(3)
        node.get("/shop/cart/c-1/state").status.assert().isEqualTo(200)
        node.get("/shop/cart/c-1/state?version=1").status.assert().isEqualTo(200)
        node.get("/shop/cart/c-1/state?at=1970-01-01T00:00:02Z").status.assert().isEqualTo(200)
        node.get("/shop/cart/c-1/state/trace?from=2").status.assert().isEqualTo(200)
        val refused = node.get("/shop/cart/c-1/state?version=1", user = "bob")
        refused.assertNotFound()
        // The caller learns nothing of why.
        refused.body!!["detail"].asString().assert().isEqualTo("No state of $ref.")
        refused.body!!.has("violations").assert().isFalse()
        node.get("/shop/cart/c-2/state").assertNotFound()
        node.get("/shop/cart/c-1/state?version=0").assertInvalid(QueryErrorCodes.INVALID_VALUE, "version")
        ended.assert().containsExactly(
            "complete:STATE",
            "complete:STATE",
            "complete:STATE",
            "complete:TRACE",
            "error:STATE",
            "error:STATE",
            "error:STATE",
        )
        audits.map { it.queryType }.assert().containsExactly(
            QueryType.STATE,
            QueryType.STATE,
            QueryType.STATE,
            QueryType.TRACE,
            QueryType.STATE,
            QueryType.STATE,
            QueryType.STATE,
        )
        audits.map { it.rows }.assert().containsExactly(1L, 1L, 1L, 2L, 0L, 0L, 0L)
        audits.map { it.errorCode }.assert().containsExactly(
            null,
            null,
            null,
            null,
            "wow.aggregate.not-found:OUT_OF_SCOPE",
            "wow.aggregate.not-found:MISSING",
            "wow.query.invalid:INVALID_VALUE",
        )
        audits[4].caller.caller!!.subject.assert().isEqualTo("bob")
        audits.map { it.model }.toSet().assert().containsExactly(null)
        // The shape of a read, never its id, version or time.
        audits[0].fingerprint.assert().isNotEqualTo(audits[1].fingerprint)
        audits[1].fingerprint.assert().isEqualTo(audits[4].fingerprint)
    }

    private fun Answer.assertNotFound() = assertCode(404, FrameworkCodes.NOT_FOUND)

    private fun Answer.assertInvalid(violation: String, path: String) {
        assertCode(400, FrameworkErrors.QUERY_INVALID)
        val first = body!!["violations"][0]
        first["code"].asString().assert().isEqualTo(violation)
        first["path"].asString().assert().isEqualTo(path)
    }

    private fun Answer.assertCode(status: Int, code: String) {
        this.status.assert().isEqualTo(status)
        contentType.assert().isEqualTo("application/problem+json")
        body!!["code"].asString().assert().isEqualTo(code)
    }

    private companion object {
        /** More commits than a state read replays by default, past the snapshot of version 10. */
        const val LONG = 10_050
        const val MAX_REPLAY = KernelStateReads.DEFAULT_MAX_REPLAY
    }
}
