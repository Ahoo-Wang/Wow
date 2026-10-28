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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.http.query.QueryStreams
import me.ahoo.wow.manifest.FrameworkErrors
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.backend.PositionedRecord
import me.ahoo.wow.query.error.QueryBusyException
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.json.QueryJson
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/** Streamed lists are resumable (decision D253): ids, `Last-Event-ID`, the `end` event and the hold time. */
class ResumableStreamRoutesTest {
    private val byItems = """{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"state.items","direction":"asc"}]}"""

    private fun QueryNode.saveFive() = (1..5).forEach { save("c-$it", items = it) }

    private fun ids(streamed: Streamed): List<String> = streamed.rows.map { it.data["aggregateId"].asString() }

    @Test
    fun `a stream resumed after an id continues strictly after it, across chunks`() {
        val node = QueryNode(streams = QueryStreams(chunkSize = 2))
        node.saveFive()
        val all = node.stream(byItems)
        ids(all).assert().containsExactly("c-1", "c-2", "c-3", "c-4", "c-5")
        all.end!!["reason"].asString().assert().isEqualTo(QueryStreams.COMPLETE)
        // Resumed after the second record, the first of the second chunk is next.
        val resumed = node.stream(byItems, lastEventId = all.rows[1].id)
        ids(resumed).assert().containsExactly("c-3", "c-4", "c-5")
        resumed.rows.map { it.id }.assert().isEqualTo(all.rows.drop(2).map { it.id })
        // Resumed after the last record: nothing more, complete.
        node.stream(byItems, lastEventId = all.rows.last().id).events.map { it.event }.assert().containsExactly("end")
    }

    @Test
    fun `a sort that is not unique has the unique key appended, so no record repeats or goes missing`() {
        val node = QueryNode(streams = QueryStreams(chunkSize = 2))
        (1..5).forEach { node.save("c-$it", items = 7) }
        val body = """{"kind":"list","filter":{"op":"match-all"},"sort":[{"field":"state.items","direction":"desc"}]}"""
        val all = node.stream(body)
        ids(all).assert().hasSize(5).doesNotHaveDuplicates()
        ids(node.stream(body, lastEventId = all.rows[2].id)).assert().isEqualTo(ids(all).drop(3))
    }

    @Test
    fun `a resumed stream is admitted again under its caller's scope`() {
        val node = QueryNode(streams = QueryStreams(chunkSize = 2))
        node.saveFive()
        (6..7).forEach { node.save("c-$it", owner = "bob", items = it) }
        val alice = node.stream(byItems)
        ids(alice).assert().containsExactly("c-1", "c-2", "c-3", "c-4", "c-5")
        // Bob resuming after one of Alice's positions sees his own records after it, never hers.
        ids(node.stream(byItems, user = "bob", lastEventId = alice.rows[0].id)).assert().containsExactly("c-6", "c-7")
    }

    @Test
    fun `neither the list default nor the row cap ends a stream, but its own limit does`() {
        val node = QueryNode(defaultListSize = 2, streams = QueryStreams(chunkSize = 2))
        node.saveFive()
        ids(node.stream(byItems)).assert().hasSize(5)
        val limited = node.stream(byItems.replace("}]}", "}],\"limit\":3}"))
        ids(limited).assert().containsExactly("c-1", "c-2", "c-3")
        limited.end!!["reason"].asString().assert().isEqualTo(QueryStreams.COMPLETE)
    }

    @Test
    fun `an invalid or foreign Last-Event-ID is wow-query-invalid`() {
        val node = QueryNode()
        node.saveFive()
        node.http.post("/shop/cart/c-1/add-item", """{"sku":"apple"}""", "alice").status.assert().isEqualTo(200)
        fun invalid(streamed: Streamed) {
            streamed.status.assert().isEqualTo(400)
            QueryJson.mapper.readTree(
                streamed.body
            )["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
        }
        invalid(node.stream(byItems, lastEventId = "not-a-token"))
        // A position of another sort, or of another model.
        val bySnapshotId = node.stream("""{"kind":"list","filter":{"op":"match-all"}}""").rows.first().id
        invalid(node.stream(byItems, lastEventId = bySnapshotId))
        val ofEvents = node.stream("""{"kind":"list","filter":{"op":"match-all"}}""", QueryRoutes.EVENTS).rows.first()
        invalid(node.stream("""{"kind":"list","filter":{"op":"match-all"}}""", lastEventId = ofEvents.id))
    }

    @Test
    fun `a stream ends resumable once its hold time passes, and resumes to the end`() {
        val node = QueryNode(
            snapshots = { delegate -> Slowed(delegate) },
            streams = QueryStreams(chunkSize = 2, maxHold = 120.milliseconds),
        )
        node.saveFive()
        val read = mutableListOf<String>()
        var lastEventId: String? = null
        var ends = 0
        do {
            val streamed = node.stream(byItems, lastEventId = lastEventId)
            read += ids(streamed)
            lastEventId = streamed.rows.lastOrNull()?.id ?: lastEventId
            val end = streamed.end!!
            if (end["reason"].asString() == QueryStreams.HOLD_EXPIRED) {
                end["resumable"].asBoolean().assert().isTrue()
                ends++
            }
        } while (end["reason"].asString() != QueryStreams.COMPLETE)
        read.assert().containsExactly("c-1", "c-2", "c-3", "c-4", "c-5")
        ends.assert().isGreaterThan(0)
    }

    @Test
    fun `a hold time of zero never ends a stream`() {
        val node = QueryNode(
            snapshots = { delegate -> Slowed(delegate) },
            streams = QueryStreams(chunkSize = 2, maxHold = kotlin.time.Duration.ZERO),
        )
        node.saveFive()
        val streamed = node.stream(byItems)
        ids(streamed).assert().hasSize(5)
        streamed.end!!["reason"].asString().assert().isEqualTo(QueryStreams.COMPLETE)
    }

    @Test
    fun `a stream whose store has no permit for its next chunk ends permit-reclaimed`() {
        val busy = QueryNode(snapshots = { delegate -> Busy(delegate, afterFirst = true) })
        busy.saveFive()
        val streamed = busy.stream(byItems)
        streamed.rows.assert().hasSize(1)
        streamed.end!!["reason"].asString().assert().isEqualTo(QueryStreams.PERMIT_RECLAIMED)
        streamed.end!!["resumable"].asBoolean().assert().isTrue()
        // Busy before the first record: the 429 problem.
        val refused = QueryNode(snapshots = { delegate -> Busy(delegate, afterFirst = false) }).stream(byItems)
        refused.status.assert().isEqualTo(429)
        QueryJson.mapper.readTree(refused.body)["code"].asString().assert().isEqualTo(QueryErrors.BUSY)
    }

    @Test
    fun `closing the streams ends the open ones with node-stopping and refuses new ones`() {
        val streams = QueryStreams()
        val node = QueryNode(snapshots = { delegate -> Closing(delegate, streams) }, streams = streams)
        node.saveFive()
        val streamed = node.stream(byItems)
        streamed.rows.assert().hasSize(1)
        streamed.end!!["reason"].asString().assert().isEqualTo(QueryStreams.NODE_STOPPING)
        streamed.end!!["resumable"].asBoolean().assert().isTrue()
        val refused = node.stream(byItems)
        refused.status.assert().isEqualTo(503)
        QueryJson.mapper.readTree(refused.body)["code"].asString().assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        streams.closed.assert().isTrue()
    }

    @Test
    fun `a list that cannot be resumed streams without ids, ends unresumable, and refuses a Last-Event-ID`() {
        val node = QueryNode(snapshots = { delegate -> Unresumable(delegate) }, defaultListSize = 2)
        node.saveFive()
        val streamed = node.stream(byItems)
        streamed.events.map { it.event }.assert().containsExactly("row", "row", "end")
        streamed.events.map { it.id }.assert().containsOnlyNulls()
        streamed.end!!["reason"].asString().assert().isEqualTo(QueryStreams.COMPLETE)
        streamed.end!!["resumable"].asBoolean().assert().isFalse()
        val refused = node.stream(byItems, lastEventId = "anything")
        refused.status.assert().isEqualTo(400)
        QueryJson.mapper.readTree(refused.body)["code"].asString().assert().isEqualTo(FrameworkErrors.QUERY_INVALID)
    }

    @Test
    fun `a list that cannot be resumed ends its hold unresumable`() {
        val node = QueryNode(
            snapshots = { delegate -> Unresumable(Slowed(delegate), slowList = true) },
            streams = QueryStreams(maxHold = 60.milliseconds),
        )
        node.saveFive()
        val streamed = node.stream(byItems)
        streamed.rows.size.assert().isLessThan(5)
        streamed.rows.map { it.id }.assert().containsOnlyNulls()
        streamed.end!!["reason"].asString().assert().isEqualTo(QueryStreams.HOLD_EXPIRED)
        streamed.end!!["resumable"].asBoolean().assert().isFalse()
    }

    /** Each record of a stream 50ms after the one before. */
    private class Slowed(private val delegate: QueryGateway) : QueryGateway by delegate {
        override fun stream(query: ListQuery, caller: QueryCaller, after: String?, chunkSize: Int) =
            delegate.stream(query, caller, after, chunkSize).onEach { delay(50.milliseconds) }
    }

    /** A store with no permit: before the first record, or for the chunk after it. */
    private class Busy(private val delegate: QueryGateway, private val afterFirst: Boolean) : QueryGateway by delegate {
        override fun stream(query: ListQuery, caller: QueryCaller, after: String?, chunkSize: Int) =
            flow<PositionedRecord> {
                if (afterFirst) emit(delegate.stream(query, caller, after, chunkSize).first())
                throw QueryBusyException("default", 1)
            }
    }

    /** Closes [streams] once the first record was read, then waits. */
    private class Closing(private val delegate: QueryGateway, private val streams: QueryStreams) :
        QueryGateway by delegate {
        override fun stream(query: ListQuery, caller: QueryCaller, after: String?, chunkSize: Int) =
            flow {
                emit(delegate.stream(query, caller, after, chunkSize).first())
                streams.close()
                awaitCancellation()
            }
    }

    /** A gateway that does not resume; with [slowList], each listed record 50ms after the one before. */
    private class Unresumable(private val delegate: QueryGateway, private val slowList: Boolean = false) :
        QueryGateway by delegate {
        override suspend fun resumes(query: ListQuery): Boolean = false

        override fun list(query: ListQuery, caller: QueryCaller) = delegate.list(query, caller).onEach {
            if (slowList) delay(50.milliseconds)
        }
    }
}
