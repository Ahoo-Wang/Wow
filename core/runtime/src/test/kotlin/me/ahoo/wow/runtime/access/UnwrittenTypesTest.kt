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
 * The walk of a type whose store a node saw created: recorded complete when nodes of its schema alone appended to it,
 * walked as any other when a node of another schema served the type meanwhile, an import claimed it, or the node did
 * not see it created; and a type without a store read again after fewer runs each time.
 */
class UnwrittenTypesTest : AccessWalkingFixtures() {
    private val schema = AccessSchema.of(model)
    private val older = "department:integer"
    private val members = "service/office#access"

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

    private fun heads(log: InMemoryEventLog) = runBlocking {
        (0 until log.partitions).associateWith { log.head(it) ?: 0L }
    }

    private fun walked() = observed.filterIsInstance<ObservedEvent.AccessStamped>()

    @Test
    fun `a store created while the node ran, by nodes of its schema alone, is recorded walked, its streams not read`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val walking = walking(log)
            walking.enter()
            walking.maintain().assert().isFalse()
            committed(log, listOf("d-1", "d-2", "d-3"), "sales", "legal")
            // The run after the first is not due: the type is read again on the third.
            walking.maintain()
            log.walk(type).assert().isNull()
            walking.maintain().assert().isFalse()
            log.walk(type).assert().isEqualTo(AccessWalk(schema, heads(log), completed = true))
            walked().assert().isEmpty()
            walking.walks().getValue(type).completed.assert().isTrue()
        }

    @Test
    fun `a store a node of another schema may have appended to is walked, though it served the type one run only`(): Unit =
        runBlocking {
            val log = InMemoryEventLog()
            val leases = InMemoryLeaseStore()
            val walking = walking(log, leases)
            walking.maintain()
            // A node of an earlier schema serves the type during the run that does not read it, then stops.
            leases.heartbeat(members, "n-0", "n-0", 30.seconds, setOf("document@${AccessSchema.hashOf(older)}"))
            walking.maintain()
            leases.leave(members, "n-0")
            unstamped(log, listOf("d-1"), "sales")
            walking.maintain()
            log.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.walked.assert().isEqualTo(1)
                it.completed.assert().isTrue()
            }
            history(log, "d-1").assert().containsExactly(access("sales"))
        }

    @Test
    fun `a store that holds a walk once found, an import's, is walked in its place`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        val walking = walking(log)
        walking.maintain()
        // An import appends the type's first streams, without attributes, and claims its walk; no node serves it.
        unstamped(log, listOf("d-1", "d-2"), "sales")
        log.claim(type, AccessWalk(AccessWalk.IMPORTING, emptyMap()), expected = null).assert().isTrue()
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
    fun `a store that held streams before the node started is walked, whoever appended them`(): Unit = runBlocking {
        val log = InMemoryEventLog()
        committed(log, listOf("d-1", "d-2"), "sales")
        val walking = walking(log)
        walking.maintain().assert().isFalse()
        log.walk(type)!!.let {
            it.walked.assert().isEqualTo(2)
            it.completed.assert().isTrue()
        }
        walked().map { it.cause }.assert().containsOnly(ObservedEvent.AccessStamped.Cause.WALK).hasSize(2)
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
            UnwrittenTypes.MAX_SKIPPED.assert().isEqualTo(16)
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
