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

package me.ahoo.wow.tck.log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.access.AggregatePage
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

/**
 * The access cases of the [EventLog] contract: stamping access attributes over streams already appended, paging a
 * type's aggregates, and claiming and advancing the walk that stamps them. They read only [EventLogFixtures]' fixtures,
 * hooks and constants; see [EventLogFixtures] for the chain of families this class is part of.
 */
abstract class EventLogAccessCases internal constructor() : EventLogRecordCases() {
    @Test
    fun `a stamp writes the attributes over its range but the streams stamped later, however often it runs`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is AccessStamps, "This log keeps no stamps.")
            val stamps = log as AccessStamps
            fun access(region: String) = linkedMapOf<String, Any?>(
                "owner" to "owner",
                "space" to null,
                "region" to region
            )
            val (east, west, north) = listOf(access("east"), access("west"), access("north"))
            // The first stream was appended without them (imported, or by a node of an earlier version).
            val history = listOf(
                stream("a", 1),
                stream("a", 2, access = east),
                stream("a", 3, access = east),
                stream("a", 4, access = west),
            )
            history.forEach { (log.append(it) is AppendOutcome.Committed).assert().isTrue() }
            val ref = type.ref("tenant", "a")
            fun stamped() = runBlocking { log.read(ref).toList().map { it.access } }
            fun versions() = runBlocking { log.read(ref).toList().map { it.accessVersion } }

            // Each stream reads as of its own version, the unstamped one as of none.
            versions().assert().containsExactly(null, 2, 3, 4)
            // The commit at 4 changed them: the streams before it take them, the unstamped one too.
            stamps.stamp(ref, west, asOf = 4, from = 1, until = 4).assert().isEqualTo(3)
            stamped().assert().containsExactly(west, west, west, west)
            versions().assert().containsExactly(4, 4, 4, 4)
            // A replay of the earlier change writes nothing over a later stamp.
            stamps.stamp(ref, east, asOf = 2, from = 1, until = 2).assert().isEqualTo(0)
            // A stamp as of the same version writes again: a model of other attributes derives them anew.
            stamps.stamp(ref, north, asOf = 4, from = 1, until = 3).assert().isEqualTo(2)
            stamped().assert().containsExactly(north, north, west, west)
            // A range past the history, and an aggregate without one, hold nothing to write.
            stamps.stamp(ref, east, asOf = 9, from = 5, until = 9).assert().isEqualTo(0)
            stamps.stamp(type.ref("tenant", "b"), east, asOf = 2, from = 1, until = 2).assert().isEqualTo(0)
            // The entries and the request keys read what the stamp wrote.
            log.readEntries(ref, 1, 1).toList().single().stream.let {
                it.access.assert().isEqualTo(north)
                it.accessVersion.assert().isEqualTo(4)
            }
            checkNotNull(log.findByRequestId(type, "tenant", history[0].requestId)).access.assert().isEqualTo(north)
            // A tail read, the subscriptions', reads the stamp too.
            withTimeout(TAIL_TIMEOUT_MS) {
                log.tail(Partitioner.partitionOf(ref, log.partitions)).take(history.size).toList()
            }.map { it.stream.accessVersion }.assert().containsExactly(4, 4, 4, 4)
        }

    @Test
    fun `a page's stamps are written together, each under its own guard, the later of two that overlap kept`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is AccessStamps, "This log keeps no stamps.")
            val stamps = log as AccessStamps
            fun access(
                region: String
            ) = linkedMapOf<String, Any?>("owner" to "owner", "space" to null, "region" to region)
            val (east, west) = listOf(access("east"), access("west"))
            listOf("a", "b", "c").forEach { id -> (1..3).forEach { log.append(stream(id, it)) } }
            val (a, b, c) = listOf("a", "b", "c").map { type.ref("tenant", it) }
            // b's first stream is stamped as of a later version already.
            stamps.stamp(b, west, asOf = 9, from = 1, until = 2).assert().isEqualTo(1)
            stamps.stampAll(
                listOf(
                    AccessStamp(a, east, asOf = 3, from = 1, until = 3),
                    AccessStamp(a, east, asOf = 3, from = 3, until = 4),
                    AccessStamp(b, east, asOf = 3, from = 1, until = 4),
                    AccessStamp(type.ref("tenant", "none"), east, asOf = 1, from = 1, until = 2),
                ),
            ).assert().isEqualTo(5)
            suspend fun versions(ref: AggregateRef) = log.read(ref).toList().map { it.accessVersion }
            versions(a).assert().containsExactly(3, 3, 3)
            versions(b).assert().containsExactly(9, 3, 3)
            log.read(b).toList().map { it.access }.assert().containsExactly(west, east, east)
            versions(c).assert().containsExactly(null, null, null)
            // Two that overlap leave the later version's, in whatever order they are given.
            stamps.stampAll(
                listOf(
                    AccessStamp(c, west, asOf = 7, from = 1, until = 4),
                    AccessStamp(c, east, asOf = 5, from = 1, until = 4),
                ),
            )
            versions(c).assert().containsExactly(7, 7, 7)
            log.read(c).toList().map { it.access }.distinct().assert().containsExactly(west)
            stamps.stampAll(emptyList()).assert().isEqualTo(0)
        }

    @Test
    fun `a type's aggregates are paged once each, in the log's order, whatever their tenants`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is AccessWalks, "This log keeps no walks.")
            val walks = log as AccessWalks
            val refs = (1..7).flatMap { listOf(type.ref("tenant", "a-$it"), type.ref("tenant ü", "a-$it")) }
            refs.forEach { ref ->
                log.append(stream(ref.id, 1, tenant = ref.tenantId))
                log.append(stream(ref.id, 2, tenant = ref.tenantId))
            }
            log.append(stream("a-1", 1, aggregate = other))
            val pages = ArrayList<AggregatePage>()
            var after: String? = null
            do {
                val page = walks.aggregates(type, after, limit = 3)
                pages += page
                after = page.next
            } while (after != null)
            pages.flatMap { it.refs }.assert().containsExactlyInAnyOrderElementsOf(refs)
            pages.dropLast(1).forEach { it.refs.assert().hasSize(3) }
            val none = walks.aggregates(AggregateType(context, "none"), null, limit = 3)
            none.assert().isEqualTo(AggregatePage(emptyList(), null))
        }

    @Test
    fun `a type the log holds no history of has no walk and claims none, and its first append creates its store`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is AccessWalks, "This log keeps no walks.")
            val walks = log as AccessWalks
            val walk = AccessWalk("", mapOf(0 to 1L))
            walks.holds(type).assert().isFalse()
            walks.walk(type).assert().isNull()
            walks.claim(type, walk, expected = null).assert().isFalse()
            walks.advance(type, "", null, walked = 0, completed = true).assert().isFalse()
            walks.aggregates(type, null, limit = 3).assert().isEqualTo(AggregatePage(emptyList(), null))
            // None of them created the type's store.
            walks.holds(type).assert().isFalse()

            log.append(stream("a-1", 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            walks.holds(type).assert().isTrue()
            walks.holds(other).assert().isFalse()
            if (opensOneStorePerContext) {
                // Another node of the service finds the store its first append created.
                (createLog() as AccessWalks).holds(type).assert().isTrue()
            }
            walks.claim(type, walk, expected = null).assert().isTrue()
            walks.walk(type).assert().isEqualTo(walk)
            walks.aggregates(type, null, limit = 3).refs.assert().containsExactly(type.ref("tenant", "a-1"))
        }

    @Test
    fun `a log told its node's schema records the walk completed as it creates a type's store, an existing one kept`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is AccessWalks, "This log keeps no walks.")
            val walks = log as AccessWalks
            walks.serve(mapOf(type to "region:string"))
            walks.walk(type).assert().isNull()
            log.append(stream("a-1", 1)).assert().isInstanceOf(AppendOutcome.Committed::class.java)
            walks.walk(type).assert().isEqualTo(AccessWalk.created("region:string"))
            // A type it serves no schema for records none.
            log.append(stream("b-1", 1, aggregate = other))
            walks.walk(other).assert().isNull()
            if (opensOneStorePerContext) {
                // Another node, of another schema, makes sure of the store the first created: the walk stays.
                val second = createLog()
                val secondWalks = second as AccessWalks
                secondWalks.serve(mapOf(type to "teams:string[]"))
                second.append(stream("a-2", 1))
                secondWalks.walk(type).assert().isEqualTo(AccessWalk.created("region:string"))
            }
        }

    @Test
    fun `a walk is claimed by its schema, once among concurrent claims, and advances while recorded`(): Unit =
        runBlocking {
            val log = createLog()
            Assumptions.assumeTrue(log is AccessWalks, "This log keeps no walks.")
            val walks = log as AccessWalks
            log.append(stream("a-1", 1))
            walks.walk(type).assert().isNull()
            val first = AccessWalk("region:string", mapOf(0 to 4L, 1 to 0L))
            walks.claim(type, first, expected = "other").assert().isFalse()
            val claims = (1..4).map { async { walks.claim(type, first, expected = null) } }.awaitAll()
            claims.count { it }.assert().isEqualTo(1)
            walks.walk(type).assert().isEqualTo(first)
            walks.walk(other).assert().isNull()

            walks.advance(type, "region:string", "2:a-b", walked = 2, completed = false).assert().isTrue()
            walks.walk(type).assert().isEqualTo(first.copy(cursor = "2:a-b", walked = 2))
            walks.advance(type, "other", null, walked = 9, completed = true).assert().isFalse()
            walks.advance(other, "region:string", null, walked = 9, completed = true).assert().isFalse()

            // A new schema takes the place of the recorded one, from its start.
            val second = AccessWalk("region:string,teams:string[]", mapOf(0 to 9L))
            walks.claim(type, second, expected = null).assert().isFalse()
            walks.claim(type, second, expected = "region:string").assert().isTrue()
            walks.walk(type).assert().isEqualTo(second)
            walks.advance(type, second.schema, null, walked = 5, completed = true).assert().isTrue()
            walks.walk(type).assert().isEqualTo(second.copy(walked = 5, completed = true))
        }
}
