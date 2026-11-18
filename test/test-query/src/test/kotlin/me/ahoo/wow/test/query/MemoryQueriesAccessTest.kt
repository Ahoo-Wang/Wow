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

package me.ahoo.wow.test.query

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.access.AccessPolicy
import me.ahoo.wow.api.access.accessPolicy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.test.query.InventoryModels.Companion.SHELF
import me.ahoo.wow.test.query.InventoryModels.Companion.STOCK
import me.ahoo.wow.test.query.InventoryModels.Shelf
import me.ahoo.wow.test.query.InventoryModels.Stock
import me.ahoo.wow.test.reactorSpec
import me.ahoo.wow.test.sagaSpec
import me.ahoo.wow.test.stateEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An aggregate that declares access attributes and has no policy is refused where a spec reads it (#6465), not when
 * its read side is built: a spec that never reads it runs, and one that does names the policy it reads under.
 */
class MemoryQueriesAccessTest {
    private val refusal = "The aggregate inventory.shelf declares the access attributes [zone], but no access " +
        "policy is given for it: declare an AccessPolicy bean of its state (accessPolicy { read { … }; write { … } " +
        "}), since nothing is granted by omission; a spec that reads it passes the policy to memoryQueries(access = …)."

    private val zone = AccessAttribute.single<Shelf, String>("zone", String::class.java)

    private val zoneA = accessPolicy<Shelf> {
        read { zone eq "a" }
        write { denyAll() }
    }

    private fun shelves(vararg access: AccessPolicy<*>) =
        memoryQueries(access = access.toList()) {
            snapshot(SHELF, "s-a", Shelf("a"), tenantId = "acme", access = mapOf("zone" to "a"))
            snapshot(SHELF, "s-b", Shelf("b"), tenantId = "acme", access = mapOf("zone" to "b"))
        }

    @Test
    fun `a spec that never reads the aggregate should run without its policy`(): Unit = runBlocking {
        val queries = memoryQueries { snapshots(STOCK, "mug" to Stock(7), tenantId = "acme") }
        queries.of(Caller.of(tenantId = "acme")).snapshots<Stock>().state("mug")!!.state.assert().isEqualTo(Stock(7))
    }

    @Test
    fun `a query or a state read of the aggregate should be refused, naming the way out for a spec`(): Unit =
        runBlocking {
            val queries = shelves()
            assertThrows<IllegalStateException> {
                queries.of(Caller.of(tenantId = "acme")).snapshots<Shelf>().list(listQuery { }).toList()
            }.message.assert().isEqualTo(refusal)
            assertThrows<IllegalStateException> {
                queries.trusted().snapshots<Shelf>().list(listQuery { }).toList()
            }.message.assert().isEqualTo(refusal)
            assertThrows<IllegalStateException> {
                queries.trusted().snapshots<Shelf>().state(SHELF.ref("acme", "s-a"))
            }.message.assert().isEqualTo(refusal)
            assertThrows<IllegalStateException> { queries.tenant("acme").snapshots<Shelf>().state("s-a") }
                .message.assert().isEqualTo(refusal)
        }

    @Test
    fun `a spec that passes the policy should read under it`(): Unit = runBlocking {
        val queries = shelves(zoneA).of(Caller.of(tenantId = "acme")).snapshots<Shelf>()
        queries.list(listQuery { }).toList().map { it.ref.id }.assert().containsExactly("s-a")
        queries.state("s-a")!!.state.assert().isEqualTo(Shelf("a"))
        queries.state("s-b").assert().isNull()
    }

    class Picked(val shelf: String)

    data class Restock(val shelf: String)

    private fun picked(shelf: String): StateEvent<Picked> =
        stateEvent(AggregateType("shop", "pick").ref("acme", "p-1"), Picked(shelf), Restock(shelf))

    @Test
    fun `a saga's or a reactor's read of the aggregate should be refused, and run under a policy given`() {
        val saga = ProcessorBinding.saga(
            "PickSaga",
            "onPicked",
            Picked::class.java,
            derives = listOf(Restock::class.java),
        ) { event: StateEvent<Picked>, context ->
            val shelf = context.queries.snapshots<Shelf>().state(event.state.shelf)
            listOfNotNull(shelf?.let { CommandRequest.to(STOCK.ref("acme", "mug"), Restock(it.state.zone)) })
        }
        sagaSpec(listOf(saga)) {
            givenQueries(shelves())
            whenEvent(picked("s-a")) { expectError<IllegalStateException> { message.assert().isEqualTo(refusal) } }
            givenQueries(shelves(zoneA))
            whenEvent(picked("s-a")) { expectCommands(Restock("a")) }
        }
        val reactor = ProcessorBinding.react(
            "PickWatcher",
            "onPicked",
            Picked::class.java,
        ) { _: StateEvent<Picked>, context ->
            context.queries.snapshots<Shelf>().list(listQuery { }).toList()
            Unit
        }
        reactorSpec(listOf(reactor)) {
            givenQueries(shelves())
            whenEvent(picked("s-a")) { expectError<IllegalStateException> { message.assert().isEqualTo(refusal) } }
            givenQueries(shelves(zoneA))
            whenEvent(picked("s-a")) { expectNoError() }
        }
    }
}
