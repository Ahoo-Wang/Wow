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

package me.ahoo.wow.runtime.admission

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.id.AggregateIdGenerator
import me.ahoo.wow.spi.id.IdGenerators
import me.ahoo.wow.spi.log.CreationClaim
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

/** The ids of the creation commands that name none (decision D191). */
class CreationIdsTest {
    data class Open(val note: String = "")
    data class Touch(val note: String = "")
    data class Opened(val note: String)

    private val type = AggregateType("shop", "order")
    private val model = aggregate<Any>(type.context, type.name) {
        initial { Any() }
        decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.note)) }
        decide<Touch> { _, _ -> Decision.NoOp }
        evolve<Opened> { }
    }

    /** One partition: a caller of another scope then takes the first id drawn ([Claims] has four). */
    private val log = InMemoryEventLog(partitions = 1)
    private var drawn = 0
    private val creations = CreationIds(
        IdGenerators(aggregates = AggregateIdGenerator { "${it.name}-n${++drawn}" }),
        CommittedRequests.of(log),
    )

    private suspend fun committed(requestId: String, id: String, scope: Scope) {
        log.append(
            EventStreamRecord(
                ref = type.ref("t", id),
                version = 1,
                commandId = "c-$requestId",
                requestId = requestId,
                commandType = "shop.order.Open",
                commitTime = 1L,
                events = listOf(EventRecord("shop.order.Opened", 1, "{}")),
                scope = scope,
            ),
            null,
        )
    }

    @Test
    fun `a request that committed keeps its id within its scope, any other draws a new one`(): Unit = runBlocking {
        committed("r-1", "o-1", Scope("alice", "s-1"))
        creations.idOf(type, "t", "r-1", Scope("alice", "s-1")).assert().isEqualTo("o-1")
        creations.idOf(type, "t", "r-1").assert().isEqualTo("o-1")
        drawn.assert().isEqualTo(0)
        creations.idOf(type, "t", "r-1", Scope("bob", "s-1")).assert().isEqualTo("order-n1")
        creations.idOf(type, "t", "r-1", Scope("alice", "s-2")).assert().isEqualTo("order-n2")
        // An aggregate of its tenant checks the space alone (decision D189).
        creations.idOf(type, "t", "r-1", Scope("bob", "s-1"), Ownership.TENANT).assert().isEqualTo("o-1")
        creations.idOf(type, "t", "r-2").assert().isEqualTo("order-n3")
        creations.idOf(type, "other", "r-1").assert().isEqualTo("order-n4")
    }

    @Test
    fun `a command that names its aggregate is kept, one that names none is given its id`(): Unit = runBlocking {
        val named = Command("c-1", "r-1", type.ref("t", "o-9"), Open())
        creations.assign(model, named).assert().isSameAs(named)
        val unnamed = Command("c-2", "r-2", type.unassigned("t"), Open())
        unnamed.ref.isAssigned.assert().isFalse()
        val assigned = creations.assign(model, unnamed)
        assigned.ref.assert().isEqualTo(type.ref("t", "order-n1"))
        assigned.copyWith(ref = unnamed.ref).assert().isEqualTo(unnamed)
        runCatching { creations.assign(model, Command("c-3", "r-3", type.unassigned("t"), Touch())) }
            .exceptionOrNull()!!.message.assert().contains("names no shop.order to act on")
        runCatching { creations.assign(model, Command("c-4", "r-4", type.unassigned("t"), "unknown")) }
            .exceptionOrNull().assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `a command to an aggregate whose id is its owner's takes its scope's owner, and fails without one`(): Unit =
        runBlocking {
            val basket = aggregate<Any>(type.context, "basket") {
                id(AggregateIdSource.OWNER)
                initial { Any() }
                decide<Open>(Creation.REQUIRED) { _, body -> Decision.events(Opened(body.note)) }
                decide<Touch> { _, _ -> Decision.NoOp }
                evolve<Opened> { }
            }
            val unnamed = Command("c-1", "r-1", basket.type.unassigned("t"), Touch(), scope = Scope("alice", "s-1"))
            creations.assign(basket, unnamed).ref.assert().isEqualTo(basket.type.ref("t", "alice"))
            val opening = Command("c-2", "r-2", basket.type.unassigned("t"), Open(), scope = Scope(ownerId = "bob"))
            creations.assign(basket, opening).ref.assert().isEqualTo(basket.type.ref("t", "bob"))
            drawn.assert().isEqualTo(0)
            val named = Command("c-3", "r-3", basket.type.ref("t", "carol"), Touch(), scope = Scope(ownerId = "alice"))
            creations.assign(basket, named).assert().isSameAs(named)
            runCatching { creations.assign(basket, Command("c-4", "r-4", basket.type.unassigned("t"), Touch())) }
                .exceptionOrNull()!!.message.assert().contains("has no owner to take it from")
        }

    /** The ids of [committed], drawn as `<type>-n<count>`. */
    private fun drawing(committed: CommittedRequests) =
        CreationIds(IdGenerators(aggregates = AggregateIdGenerator { "${it.name}-n${++drawn}" }), committed)

    /** A log of four partitions that claims, as MongoDB's: the request's first claim holds. */
    private inner class Claims(private val log: InMemoryEventLog = InMemoryEventLog(partitions = PARTITIONS)) :
        CommittedRequests {
        val held = ConcurrentHashMap<String, CreationClaim>()

        override suspend fun find(type: AggregateType, tenantId: String, requestId: String): EventStreamRecord? =
            log.findByRequestId(type, tenantId, requestId)

        override suspend fun claim(
            type: AggregateType,
            tenantId: String,
            requestId: String,
            claim: CreationClaim,
        ): CreationClaim = held.putIfAbsent("$type/$tenantId/$requestId", claim) ?: claim

        override fun partitionOf(ref: AggregateRef): Int = Partitioner.partitionOf(ref, PARTITIONS)
    }

    @Test
    fun `every attempt of a request in its scope takes the id the first claimed, each drawing one`(): Unit =
        runBlocking {
            val claims = Claims()
            val creations = drawing(claims)
            creations.idOf(type, "t", "r-1", Scope("alice", "s-1")).assert().isEqualTo("order-n1")
            // A retry while the first attempt is still deciding: nothing committed yet, the claim holds.
            creations.idOf(type, "t", "r-1", Scope("alice", "s-1")).assert().isEqualTo("order-n1")
            creations.idOf(type, "t", "r-1", Scope("alice")).assert().isEqualTo("order-n1")
            drawn.assert().isEqualTo(3)
            claims.held.values.single().assert().isEqualTo(CreationClaim.of("order-n1", Scope("alice", "s-1")))
            // Another request, tenant or type claims apart.
            creations.idOf(type, "t", "r-2", Scope("alice", "s-1")).assert().isEqualTo("order-n4")
            creations.idOf(type, "other", "r-1", Scope("alice", "s-1")).assert().isEqualTo("order-n5")
        }

    @Test
    fun `a caller of another scope gets an id of its own, in the partition of the request's aggregate`(): Unit =
        runBlocking {
            val claims = Claims()
            val creations = drawing(claims)
            val claimed = creations.idOf(type, "t", "r-1", Scope("alice", "s-1"))
            val stranger = creations.idOf(type, "t", "r-1", Scope("bob", "s-1"))
            stranger.assert().isNotEqualTo(claimed)
            claims.partitionOf(type.ref("t", stranger)).assert().isEqualTo(claims.partitionOf(type.ref("t", claimed)))
            // An aggregate of its tenant checks the space alone (decision D189): the claim answers it.
            creations.idOf(type, "t", "r-1", Scope("bob", "s-1"), Ownership.TENANT).assert().isEqualTo(claimed)
        }

    @Test
    fun `a caller of another scope reusing a committed request draws in the partition of its aggregate`(): Unit =
        runBlocking {
            val log = InMemoryEventLog(partitions = PARTITIONS)
            val claims = Claims(log)
            val creations = drawing(claims)
            log.append(
                EventStreamRecord(
                    ref = type.ref("t", "o-1"),
                    version = 1,
                    commandId = "c-1",
                    requestId = "r-1",
                    commandType = "shop.order.Open",
                    commitTime = 1L,
                    events = listOf(EventRecord("shop.order.Opened", 1, "{}")),
                    scope = Scope("alice", "s-1"),
                ),
                null,
            )
            val stranger = creations.idOf(type, "t", "r-1", Scope("bob", "s-1"))
            stranger.assert().isNotEqualTo("o-1")
            claims.partitionOf(type.ref("t", stranger)).assert().isEqualTo(claims.partitionOf(type.ref("t", "o-1")))
            claims.held.assert().isEmpty()
        }

    @Test
    fun `a generator that gives no id in the partition of the request's aggregate fails the stranger`(): Unit =
        runBlocking {
            val claims = Claims()
            val elsewhere = generateSequence(1) { it + 1 }.map { "order-$it" }.first {
                claims.partitionOf(type.ref("t", it)) != claims.partitionOf(type.ref("t", "order-0"))
            }
            val creations = CreationIds(
                IdGenerators(aggregates = AggregateIdGenerator { if (drawn++ == 0) "order-0" else elsewhere }),
                claims,
            )
            creations.idOf(type, "t", "r-1", Scope("alice")).assert().isEqualTo("order-0")
            assertThrownBy<IllegalStateException> { runBlocking { creations.idOf(type, "t", "r-1", Scope("bob")) } }
                .hasMessageContaining("gave no id in partition")
        }

    @Test
    fun `the committed requests of each type are its log's, nothing for a type without one`(): Unit = runBlocking {
        val log = InMemoryEventLog(partitions = PARTITIONS)
        val claim = CreationClaim.of("o-1", Scope("alice"))
        val unserved = AggregateType("elsewhere", "order")
        val committed = CommittedRequests.of { if (it == type) log else null }
        committed.find(type, "t", "r-1").assert().isNull()
        committed.claim(type, "t", "r-1", claim).assert().isSameAs(claim)
        committed.partitionOf(type.ref("t", "o-1")).assert()
            .isEqualTo(Partitioner.partitionOf(type.ref("t", "o-1"), PARTITIONS))
        committed.find(unserved, "t", "r-1").assert().isNull()
        committed.claim(unserved, "t", "r-1", claim).assert().isSameAs(claim)
        committed.partitionOf(unserved.ref("t", "o-1")).assert().isEqualTo(0)
    }

    @Test
    fun `without committed requests every creation draws a new id`(): Unit = runBlocking {
        CommittedRequests.NONE.find(type, "t", "r-1").assert().isNull()
        val claim = CreationClaim.of("fresh", Scope.NONE)
        CommittedRequests.NONE.claim(type, "t", "r-1", claim).assert().isSameAs(claim)
        CommittedRequests.NONE.partitionOf(type.ref("t", "fresh")).assert().isEqualTo(0)
        CommittedRequests.of(InMemoryEventLog(partitions = PARTITIONS)).let { committed ->
            committed.claim(type, "t", "r-1", claim).assert().isSameAs(claim)
            committed.partitionOf(type.ref("t", "o-1")).assert()
                .isEqualTo(Partitioner.partitionOf(type.ref("t", "o-1"), PARTITIONS))
        }
        CreationIds(IdGenerators(aggregates = AggregateIdGenerator { "fresh" }), CommittedRequests.NONE)
            .idOf(type, "t", "r-1")
            .assert().isEqualTo("fresh")
    }

    private companion object {
        const val PARTITIONS = 4
    }
}
