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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.observation.ObservedEvent
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The walk a type's store records when a log its node serves creates it, completed with nothing walked, so neither
 * this node nor another walks it; a store another writer created is claimed and walked; and a type without a store is
 * read again after fewer runs each time.
 */
class AccessWalkCreationTest : AccessWalkingFixtures() {
    private val schema = AccessSchema.of(model)
    private val members = "service/office#access"
    private val older = "department:integer"

    private suspend fun InMemoryLeaseStore.olderNode() =
        heartbeat(members, "n-0", "n-0", 30.seconds, setOf("document@${AccessSchema.hashOf(older)}"))

    /** Appends the streams of [ids], each moved to [departments] in turn, as a node of this schema commits them. */
    private fun committed(log: InMemoryEventLog, ids: List<String>, vararg departments: String) = runBlocking {
        val kernel = Kernel(log)
        ids.forEach { id ->
            departments.forEachIndexed { index, moved ->
                val ref = type.ref("tenant", id)
                kernel.execute(model, Command("c-$id-$index", "r-$id-$index", ref, Move(moved), scope = Scope("alice")))
            }
        }
    }

    private fun walked() = observed.filterIsInstance<ObservedEvent.AccessStamped>()

    @Test
    fun `a store created by a log this node serves records its walk completed, its streams not read`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val walking = walking(log)
            walking.serve()
            walking.maintain().assert().isFalse()
            log.walk(type).assert().isNull()
            committed(log, listOf("d-1", "d-2", "d-3"), "sales", "legal")
            log.walk(type).assert().isEqualTo(AccessWalk.created(schema))
            // The run after the first is not due; the third finds the store and its walk, and claims nothing.
            walking.maintain()
            walking.maintain().assert().isFalse()
            log.walk(type).assert().isEqualTo(AccessWalk.created(schema))
            walking.walks().getValue(type).let {
                it.completed.assert().isTrue()
                it.walked.assert().isZero()
            }
            walked().assert().isEmpty()
        }

    @Test
    fun `a second node started on a store the first created claims nothing`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val leases = InMemoryLeaseStore()
        walking(log, leases).serve()
        committed(log, listOf("d-1", "d-2"), "sales")
        val second = walkingOf(log, leases)
        second.serve()
        second.maintain().assert().isFalse()
        log.walk(type).assert().isEqualTo(AccessWalk.created(schema))
        second.walks().getValue(type).walked.assert().isZero()
        walked().assert().isEmpty()
    }

    @Test
    fun `a store this schema created while an older one appended is walked once the older one's nodes are gone`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            val walking = walking(log, leases)
            walking.serve()
            committed(log, listOf("d-1"), "sales")
            walking.maintain()
            // A node of the older schema, still running, appends; the walk recorded at creation stays while it serves.
            leases.olderNode()
            unstamped(log, listOf("d-2"), "legal")
            walking.maintain()
            log.walk(type).assert().isEqualTo(AccessWalk.created(schema))
            // Gone: the first run that sees none claims the walk again over the heads, and walks both aggregates.
            leases.leave(members, "n-0")
            walking.maintain().assert().isFalse()
            log.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.walked.assert().isEqualTo(2)
                it.completed.assert().isTrue()
            }
            history(log, "d-2").assert().containsExactly(access("legal"))
            // Nothing is left to claim: a later run reads the walk it recorded.
            walking.maintain().assert().isFalse()
            log.walk(type)!!.walked.assert().isEqualTo(2)
        }

    @Test
    fun `of two nodes of the schema that stays, the second takes the walk the first claimed again, and walks nothing`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            val first = walking(log, leases)
            val second = AccessWalking(
                "office",
                "service/office",
                "n-2",
                listOf(model),
                Kernel(log),
                { log },
                leases,
                options,
                observer = observer,
            )
            first.serve()
            committed(log, listOf("d-1"), "sales")
            leases.olderNode()
            unstamped(log, listOf("d-2"), "legal")
            // Each reads the walk recorded at creation, then sees the older node.
            listOf(first, second).forEach { it.maintain() }
            listOf(first, second).forEach { it.maintain() }
            leases.leave(members, "n-0")
            // The first claims it again and walks both aggregates; the second reads that walk and takes it.
            first.maintain().assert().isFalse()
            second.maintain().assert().isFalse()
            log.walk(type)!!.let {
                it.walked.assert().isEqualTo(2)
                it.completed.assert().isTrue()
            }
            second.walks().getValue(type).walked.assert().isEqualTo(2)
            walked().assert().hasSize(2)
        }

    @Test
    fun `a re-claim that finds another schema's walk recorded meanwhile takes it, and claims as for any other`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            val walking = walking(log, leases)
            walking.serve()
            committed(log, listOf("d-1"), "sales")
            walking.maintain()
            leases.olderNode()
            walking.maintain()
            leases.leave(members, "n-0")
            // Before this node's next run, a node of another schema claimed the walk in place of the creation's.
            val other = AccessWalk("other", mapOf(0 to 1L))
            log.claim(type, other, expected = schema).assert().isTrue()
            walking.maintain()
            log.walk(type).assert().isEqualTo(other)
            walking.walks().getValue(type).assert().isEqualTo(other)
            // No node serves it: the next run claims this node's schema in its place, and walks.
            walking.maintain().assert().isFalse()
            log.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.completed.assert().isTrue()
                it.walked.assert().isEqualTo(1)
            }
        }

    @Test
    fun `a store an older schema created while this one appended is walked once the older one's nodes are gone`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            // A node of the older schema creates the store, which records its walk.
            log.serve(mapOf(type to older))
            leases.olderNode()
            unstamped(log, listOf("d-1"), "sales")
            log.walk(type).assert().isEqualTo(AccessWalk.created(older))
            val walking = walking(log, leases)
            committed(log, listOf("d-2"), "legal")
            walking.maintain()
            log.walk(type).assert().isEqualTo(AccessWalk.created(older))
            // Gone: this node claims its own schema's walk in that one's place, as for any schema without nodes.
            leases.leave(members, "n-0")
            walking.maintain().assert().isFalse()
            log.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.walked.assert().isEqualTo(2)
                it.completed.assert().isTrue()
            }
            history(log, "d-1").assert().containsExactly(access("sales"))
        }

    @Test
    fun `a store a log serving no schema created records no walk, and is claimed and walked`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        committed(log, listOf("d-1", "d-2"), "sales")
        log.walk(type).assert().isNull()
        val walking = walking(log)
        walking.serve()
        walking.maintain().assert().isFalse()
        log.walk(type)!!.let {
            it.walked.assert().isEqualTo(2)
            it.completed.assert().isTrue()
        }
        walked().map { it.cause }.assert().containsOnly(ObservedEvent.AccessStamped.Cause.WALK).hasSize(2)
    }

    @Test
    fun `a store that holds an import's walk is walked in its place`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val walking = walking(log)
        walking.serve()
        walking.maintain()
        // An import appends the type's first streams, without attributes, and claims its walk in place of the one the
        // creation recorded; no node serves it.
        unstamped(log, listOf("d-1", "d-2"), "sales")
        log.claim(type, AccessWalk(AccessWalk.IMPORTING, emptyMap()), expected = schema).assert().isTrue()
        walking.maintain()
        walking.maintain()
        log.walk(type)!!.let {
            it.schema.assert().isEqualTo(schema)
            it.walked.assert().isEqualTo(2)
            it.completed.assert().isTrue()
        }
        listOf("d-1", "d-2").forEach { history(log, it).assert().containsExactly(access("sales")) }
    }

    @Test
    fun `a type without a store is read again after one run, then twice as many each time, up to sixteen`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            var run = 0
            val reads = ArrayList<Int>()
            val counting = object : EventLog by log, AccessWalks by log {
                override suspend fun holds(type: AggregateType): Boolean = log.holds(type).also { reads += run }
            }
            val walking = walkingOf(counting)
            repeat(54) {
                run = it + 1
                walking.maintain()
            }
            reads.assert().containsExactly(1, 3, 6, 11, 20, 37, 54)
            AbsentTypes.MAX_SKIPPED.assert().isEqualTo(16)
        }

    @Test
    fun `a node enters its walks' membership, serving its schema of each type`(): Unit = runBlocking {
        val leases = InMemoryLeaseStore()
        walking(InMemoryEventLog(), leases).enter()
        leases.members(members).single().let {
            it.node.assert().isEqualTo("n-1")
            it.groups.assert().containsExactly("document@${AccessSchema.hashOf(schema)}")
        }
    }
}
