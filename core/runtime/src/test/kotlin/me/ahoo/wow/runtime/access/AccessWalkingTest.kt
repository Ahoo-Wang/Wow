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

package me.ahoo.wow.runtime.access

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.AccessStamped.Cause
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The access walks: each type's history stamped with its aggregates' current attributes, where no commit did.
 */
class AccessWalkingTest : AccessWalkingFixtures() {
    private fun heads(
        log: InMemoryEventLog
    ) = runBlocking { (0 until log.partitions).associateWith { log.head(it) ?: 0L } }

    @Test
    fun `a walk stamps each aggregate's whole history with its current attributes, a page at a time, and completes`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val ids = listOf("d-1", "d-2", "d-3")
            unstamped(log, ids, "sales", "legal")
            val walking = walking(log)
            walking.maintain().assert().isFalse()
            ids.forEach { history(log, it).assert().containsExactly(access("legal"), access("legal")) }
            // One aggregate to learn the pace from, then a page of two, then the empty page after a full one.
            val walk = AccessWalk(AccessSchema.of(model), heads(log), log.aggregates(type, null, 3).next, 3, true)
            log.walk(type).assert().isEqualTo(walk)
            walking.walks().assert().isEqualTo(mapOf(type to walk))
            observed.filterIsInstance<ObservedEvent.AccessWalked>().map { it.aggregates }.assert().containsExactly(
                1,
                2,
                0
            )
            observed.filterIsInstance<ObservedEvent.AccessStamped>().filter { it.cause == Cause.WALK }.sumOf { it.streams }
                .assert().isEqualTo(6)
            // A completed walk is not walked again.
            walking.maintain().assert().isFalse()
            observed.filterIsInstance<ObservedEvent.AccessWalked>().assert().hasSize(3)
        }

    @Test
    fun `a walk resumes after the page its log records`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1", "d-2", "d-3", "d-4"), "sales")
        // Another node walked the first page, then stopped.
        val first = log.aggregates(type, null, 2)
        log.claim(type, AccessWalk(AccessSchema.of(model), heads(log), first.next, walked = 2), expected = null)
        walking(log).maintain()
        val (walked, left) = listOf("d-1", "d-2", "d-3", "d-4").partition { id -> first.refs.any { it.id == id } }
        walked.forEach { history(log, it).assert().containsExactly(null) }
        left.forEach { history(log, it).assert().containsExactly(access("sales")) }
        log.walk(type)!!.walked.assert().isEqualTo(4)
    }

    @Test
    fun `another schema's walk stays while a node of it is left, then this node's schema is claimed`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            unstamped(log, listOf("d-1"), "sales")
            val older = "department:integer"
            log.claim(type, AccessWalk(older, heads(log), completed = true), expected = null)
            val members = "service/office#access"
            leases.heartbeat(members, "n-0", "n-0", 30.seconds, setOf("document@${AccessSchema.hashOf(older)}"))
            val walking = walking(log, leases)
            walking.maintain()
            log.walk(type)!!.schema.assert().isEqualTo(older)
            history(log, "d-1").assert().containsExactly(null)

            leases.leave(members, "n-0")
            walking.maintain()
            log.walk(type)!!.let {
                it.schema.assert().isEqualTo(AccessSchema.of(model))
                it.completed.assert().isTrue()
            }
            history(log, "d-1").assert().containsExactly(access("sales"))
        }

    @Test
    fun `the follower stamps a stream without attributes after the walk's heads, and leaves one before them`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1"), "sales")
            // Claimed up to the heads, not walked yet.
            log.claim(type, AccessWalk(AccessSchema.of(model), heads(log)), expected = null)
            val walking = walking(log)
            // A node of an earlier version, still running, writes after them.
            unstamped(log, listOf("d-2"), "hr", "legal")
            val stamping = AccessStamping("office", listOf(model), Kernel(log), { log }, options, walking, observer)
            listOf("d-1", "d-2").forEach { id ->
                log.readEntries(type.ref("tenant", id)).toList().forEach {
                    stamping.subscription.handle(stamping.subscription.decoder.decode(it.stream, it.position, null))
                }
            }
            history(log, "d-1").assert().containsExactly(null)
            history(log, "d-2").assert().containsExactly(access("legal"), access("legal"))
            // Its whole history at its first stream, once.
            observed.filterIsInstance<ObservedEvent.AccessStamped>().map { it.cause to it.streams }
                .assert().containsExactly(Cause.CATCH_UP to 2)
        }

    @Test
    fun `before any walk is claimed, a stream without attributes is left to the first claim's`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val walking = walking(log)
        val position = log.readEntries(type.ref("tenant", "d-1")).toList().single().position
        walking.covers(type, position).assert().isTrue()
        walking.maintain()
        walking.covers(type, position).assert().isTrue()
        history(log, "d-1").assert().containsExactly(access("sales"))
    }

    @Test
    fun `a type without a store of its history is not claimed, and the first run due after its first append claims it`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            val walking = walking(log, leases)
            walking.maintain().assert().isFalse()
            log.holds(type).assert().isFalse()
            log.walk(type).assert().isNull()
            walking.walks().assert().isEmpty()
            // A node of an earlier schema writes the type's first stream: the next run due, the third, has heads that
            // reach it.
            val older = "department:integer"
            val served = setOf("document@${AccessSchema.hashOf(older)}")
            leases.heartbeat("service/office#access", "n-0", "n-0", 30.seconds, served)
            unstamped(log, listOf("d-1"), "sales")
            walking.maintain().assert().isFalse()
            log.walk(type).assert().isNull()
            walking.maintain().assert().isFalse()
            log.walk(type)!!.let {
                it.completed.assert().isTrue()
                it.walked.assert().isEqualTo(1)
            }
            history(log, "d-1").assert().containsExactly(access("sales"))
        }

    @Test
    fun `a log that keeps no walks has none, and its streams are no walk's`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        unstamped(log, listOf("d-1"), "sales")
        val plain = object : EventLog by log {}
        val walking = walkingOf(plain)
        walking.maintain().assert().isFalse()
        walking.walks().assert().isEmpty()
        val position = log.readEntries(type.ref("tenant", "d-1")).toList().single().position
        walking.covers(type, position).assert().isFalse()
        walking.stampCurrent(model, type.ref("tenant", "d-1"), Cause.CATCH_UP).assert().isNull()
        walking.stop()
    }

    @Test
    fun `the follower reads a walk once, none claimed below the head it read, one claimed elsewhere by its heads`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1"), "sales")
            val position = log.readEntries(type.ref("tenant", "d-1")).toList().single().position
            val walking = walking(log)
            walking.covers(type, null).assert().isFalse()
            walking.covers(type, position).assert().isTrue()
            // Known to be below the head read before no walk was found.
            walking.covers(type, position).assert().isTrue()
            // Claimed by another node before this stream: the follower's to stamp.
            log.claim(type, AccessWalk(AccessSchema.of(model), mapOf(position.partition to 0L)), expected = null)
            walking(log).covers(type, position).assert().isFalse()
            // An aggregate without a stream has nothing to stamp.
            walking.stampCurrent(model, type.ref("tenant", "none"), Cause.CATCH_UP).assert().isNull()
        }

    enum class Tier { GOLD }

    class Shelf(val id: String) {
        var tier: Tier? = null
        var open: Boolean = false
        var size: Int = 0
        val keepers: MutableList<String> = ArrayList()
    }

    @Test
    fun `an access schema names each attribute with the kind of its stored values, in name order`() {
        val shelf = aggregate<Shelf>("office", "shelf") {
            initial { Shelf(it.id) }
            access(AccessAttribute.single<Shelf, Tier>("tier", Tier::class.java)) { it.tier }
            access(AccessAttribute.single<Shelf, Boolean>("open", Boolean::class.javaObjectType)) { it.open }
            access(AccessAttribute.single<Shelf, Int>("size", Int::class.javaObjectType)) { it.size }
            access(AccessAttribute.multiple<Shelf, String>("keepers", String::class.java)) { it.keepers }
        }
        val schema = AccessSchema.of(shelf)
        schema.assert().isEqualTo("keepers:string[],open:boolean,size:integer,tier:enum")
        AccessSchema.hashOf(schema).assert().hasSize(16).isEqualTo(AccessSchema.hashOf(schema))
        AccessSchema.of(aggregate<Shelf>("office", "bare") { initial { Shelf(it.id) } }).assert().isEmpty()
    }

    @Test
    fun `a walk stamps the other stores of a type's history too, a store of another context's type not`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            unstamped(log, listOf("d-1"), "sales", "legal")
            val stamped = ArrayList<Triple<AggregateRef, Map<String, Any?>, Int>>()
            val index = object : AccessStamps {
                override suspend fun stamp(
                    ref: AggregateRef,
                    access: Map<String, Any?>,
                    asOf: Int,
                    from: Int,
                    until: Int,
                ): Int {
                    stamped += Triple(ref, access, asOf)
                    return until - from
                }
            }
            val walking = walking(log)
            walking.target(type, index)
            walking.target(AggregateType("hr", "document"), index)
            walking.maintain()
            stamped.assert().containsExactly(Triple(type.ref("tenant", "d-1"), access("legal"), 2))
        }
}
