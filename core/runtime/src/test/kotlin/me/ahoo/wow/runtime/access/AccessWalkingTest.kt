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
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.ObservedEvent.AccessStamped.Cause
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** The access walks: each type's history stamped with its aggregates' current attributes, where no commit did. */
class AccessWalkingTest {
    class Document(val id: String) {
        var department: String? = null
    }

    data class Move(val department: String)

    data class Moved(val department: String)

    private val department = AccessAttribute.single<Document, String>("department", String::class.java)
    private val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        decide<Move>(Creation.ALLOWED) { _, command -> Decision.events(Moved(command.department)) }
        evolve<Moved> { department = it.department }
        access(department) { it.department }
    }
    private val type = model.type
    private val options = AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, interval = 1.seconds)
    private val observed = ArrayList<ObservedEvent>()
    private val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            observed += event
        }
    }

    private fun access(department: String) = AccessValues.ofStream(mapOf("department" to department), Scope("alice"))

    /** Appends to [log] the streams of [ids], each moved to [departments] in turn, without attributes. */
    private fun unstamped(log: InMemoryEventLog, ids: List<String>, vararg departments: String) = runBlocking {
        val written = InMemoryEventLog()
        val kernel = Kernel(written)
        ids.forEach { id ->
            val ref = type.ref("tenant", id)
            departments.forEachIndexed { index, moved ->
                kernel.execute(model, Command("c-$id-$index", "r-$id-$index", ref, Move(moved), scope = Scope("alice")))
            }
            written.read(ref).toList().forEach { log.append(it.copyWith(access = null)) }
        }
    }

    private fun history(log: InMemoryEventLog, id: String) =
        runBlocking { log.read(type.ref("tenant", id)).toList().map { it.access } }

    private fun heads(log: InMemoryEventLog) = runBlocking { (0 until log.partitions).associateWith { log.head(it) ?: 0L } }

    private fun walking(log: InMemoryEventLog, leases: InMemoryLeaseStore = InMemoryLeaseStore()) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        leases,
        options,
        observer = observer,
    )

    @Test
    fun `a walk stamps each aggregate's whole history with its current attributes, a page at a time, and completes`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val ids = listOf("d-1", "d-2", "d-3")
            unstamped(log, ids, "sales", "legal")
            val walking = walking(log)
            walking.maintain().assert().isFalse()
            ids.forEach { history(log, it).assert().containsExactly(access("legal"), access("legal")) }
            val walk = AccessWalk(AccessSchema.of(model), heads(log), log.aggregates(type, null, 2).next, 3, true)
            log.walk(type).assert().isEqualTo(walk)
            walking.walks().assert().isEqualTo(mapOf(type to walk))
            observed.filterIsInstance<ObservedEvent.AccessWalked>().map { it.aggregates }.assert().containsExactly(2, 1)
            observed.filterIsInstance<ObservedEvent.AccessStamped>().filter { it.cause == Cause.WALK }.sumOf { it.streams }
                .assert().isEqualTo(6)
            // A completed walk is not walked again.
            walking.maintain().assert().isFalse()
            observed.filterIsInstance<ObservedEvent.AccessWalked>().assert().hasSize(2)
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
        // An empty log's walk has nothing to walk.
        val empty = InMemoryEventLog()
        walking(empty).maintain()
        empty.walk(type)!!.completed.assert().isTrue()
    }
}
