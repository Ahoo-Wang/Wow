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

package me.ahoo.wow.kernel

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.DeleteAggregate
import me.ahoo.wow.api.lifecycle.OwnerTransferred
import me.ahoo.wow.api.lifecycle.TransferOwner
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * An aggregate whose owner is fixed, one of its tenant ([Ownership.TENANT]) or one whose id is its owner's
 * ([AggregateIdSource.OWNER]), never commits `OwnerTransferred`, whatever produces it: a decide function, an after hook,
 * one whose events are known or one whose are not. The event is a bug of the model, failed as an undeclared one.
 */
class KernelFixedOwnerTest {
    private val log = InMemoryEventLog()
    private val kernel = Kernel(log, EventCodec())
    private var sequence = 0

    /** A basket whose owner is fixed: of its tenant when [tenant], else its id its owner's. */
    private fun basket(tenant: Boolean, block: AggregateModelBuilder<CartState>.() -> Unit = {}) =
        aggregate<CartState>("example", "basket") {
            if (tenant) ownership(Ownership.TENANT) else id(AggregateIdSource.OWNER)
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
            evolve<CartCreated> { owner = it.owner }
            evolve<ItemAdded> { items[it.productId] = (items[it.productId] ?: 0) + it.quantity }
            block()
        }

    private suspend fun send(model: AggregateModel<*>, body: Any, scope: Scope = Scope(ownerId = "alice")) =
        kernel.execute(
            model,
            Command("c-${++sequence}", "r-$sequence", model.type.ref("tenant", "alice"), body, scope = scope)
        )

    /** Creates alice's basket of [model], then sends [body], which fails; returns the failure. */
    private suspend fun failed(model: AggregateModel<*>, body: Any): UndeclaredEventException {
        send(model, CreateCart("alice"))
        val failure = assertThrows<UndeclaredEventException> { send(model, body) }
        failure.event.assert().isEqualTo(OwnerTransferred::class.java)
        failure.command.assert().isEqualTo(body.javaClass)
        // Nothing of the decision is committed: the creation alone is in the log.
        log.read(model.type.ref("tenant", "alice")).toList().map { it.version }.assert().containsExactly(1)
        return failure
    }

    @Test
    fun `a decide function of an aggregate of its tenant that transfers the owner should fail the command`(): Unit =
        runTest {
            val model = basket(tenant = true) {
                decide<AddItem> { _, command ->
                    Decision.events(ItemAdded(command.productId, command.quantity), OwnerTransferred("mallory"))
                }
            }
            failed(model, AddItem("apple", 1)).message.assert()
                .contains("example.basket", "AddItem", "OwnerTransferred", "Ownership.TENANT")
        }

    @Test
    fun `a decide function of an aggregate whose id is its owner's that transfers the owner should fail the command`(): Unit =
        runTest {
            val model = basket(tenant = false) {
                decide<AddItem> { _, command ->
                    Decision.events(ItemAdded(command.productId, command.quantity), OwnerTransferred("mallory"))
                }
            }
            failed(model, AddItem("apple", 1)).message.assert()
                .contains("example.basket", "AddItem", "OwnerTransferred", "AggregateIdSource.OWNER")
        }

    @Test
    fun `an after hook that transfers a fixed owner should fail the command as a decide function does`(): Unit =
        runTest {
            val model = basket(tenant = false) {
                decideSuspending<AddItem> { _, command ->
                    Decision.events(
                        ItemAdded(command.productId, command.quantity)
                    )
                }
                afterSuspending(include = setOf(AddItem::class.java)) { _, _ -> listOf(OwnerTransferred("mallory")) }
            }
            failed(model, AddItem("apple", 1))
        }

    @Test
    fun `a decision that transfers a fixed owner to the owner it has should fail the command too`(): Unit = runTest {
        // The aggregate's id is alice's, so the owner the event names is the one it has: the event is refused anyway.
        val model = basket(tenant = false) {
            decide<AddItem> { _, _ -> Decision.events(OwnerTransferred("alice")) }
        }
        failed(model, AddItem("apple", 1))
    }

    @Test
    fun `a fixed owner's model should refuse a decide function or an after hook that declares OwnerTransferred`() {
        val transfers = setOf<Class<*>>(ItemAdded::class.java, OwnerTransferred::class.java)
        listOf(true, false).forEach { tenant ->
            assertThrows<IllegalArgumentException> {
                basket(tenant) {
                    addDecider(
                        AddItem::class.java,
                        "example.basket.AddItem",
                        DecideFunction.Sync(Creation.NONE, produces = transfers) { _, _ -> Decision.NoOp },
                    )
                }
            }.message.assert().contains("OwnerTransferred", "AddItem")
            assertThrows<IllegalArgumentException> {
                basket(tenant) {
                    decide<AddItem> { _, _ -> Decision.NoOp }
                    after(produces = transfers) { _, _ -> emptyList() }
                }
            }.message.assert().contains("OwnerTransferred", "after hook")
            // Functions that declare their own events alone are built.
            val own = setOf<Class<*>>(ItemAdded::class.java)
            basket(tenant) {
                addDecider(
                    AddItem::class.java,
                    "example.basket.AddItem",
                    DecideFunction.Sync(Creation.NONE, produces = own) { _, _ -> Decision.NoOp },
                )
                after(produces = own) { _, _ -> emptyList() }
            }.decider(AddItem::class.java)?.produces.assert().isEqualTo(own)
        }
    }

    @Test
    fun `a fixed owner's model should refuse a lifecycle list that names TransferOwner, all four named too`() {
        val all = KernelLifecycle.COMMANDS.toTypedArray()
        listOf(true, false).forEach { tenant ->
            assertThrows<IllegalArgumentException> { basket(tenant) { lifecycle(*all) } }
                .message.assert().contains("example.basket", "lifecycle(...) names no TransferOwner")
            // Without the list, TransferOwner is left out; without TransferOwner, the list is taken as it is.
            basket(tenant).lifecycleCommands.assert().doesNotContain(TransferOwner::class.java).hasSize(3)
            basket(tenant) { lifecycle(DeleteAggregate::class.java) }.lifecycleCommands.assert()
                .containsExactly(DeleteAggregate::class.java)
        }
    }

    @Test
    fun `an aggregate whose owner is not fixed should take OwnerTransferred from its own command`(): Unit = runTest {
        val model = aggregate<CartState>("example", "basket") {
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command -> Decision.events(CartCreated(command.owner)) }
            decide<AddItem> { _, _ -> Decision.events(OwnerTransferred("bob")) }
            evolve<CartCreated> { owner = it.owner }
        }
        send(model, CreateCart("alice"))
        (send(model, AddItem("apple", 1)) as CommandOutcome.Committed).stream.scope.assert()
            .isEqualTo(Scope(ownerId = "bob"))
    }

    @Test
    fun `an aggregate of its tenant should still load a log that holds OwnerTransferred`(): Unit = runTest {
        // A log written before: the event was committed, and the aggregate replays past it to decide on.
        val before = aggregate<CartState>("example", "basket") {
            initial { CartState(it.id) }
            decide<CreateCart>(Creation.REQUIRED) { _, command ->
                Decision.events(CartCreated(command.owner), OwnerTransferred("mallory"))
            }
            evolve<CartCreated> { owner = it.owner }
        }
        // Sent by trusted code (no caller's scope), which the transfer leaves in reach.
        send(before, CreateCart("alice"), Scope.NONE).assert().isInstanceOf(CommandOutcome.Committed::class.java)
        val model = basket(tenant = true) {
            decide<AddItem> { _, command -> Decision.events(ItemAdded(command.productId, command.quantity)) }
        }
        val added = Kernel(log, EventCodec()).execute(
            model,
            Command("c-x", "r-x", model.type.ref("tenant", "alice"), AddItem("apple", 1)),
        ) as CommandOutcome.Committed
        added.stream.version.assert().isEqualTo(2)
        (added.state as CartState).items.assert().containsEntry("apple", 1)
    }
}
