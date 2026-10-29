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
import me.ahoo.wow.api.aggregate.AggregateIdSource
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
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test

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
    private val log = InMemoryEventLog()
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

    @Test
    fun `without committed requests every creation draws a new id`(): Unit = runBlocking {
        CommittedRequests.NONE.find(type, "t", "r-1").assert().isNull()
        CreationIds(IdGenerators(aggregates = AggregateIdGenerator { "fresh" })).idOf(type, "t", "r-1")
            .assert().isEqualTo("fresh")
    }
}
