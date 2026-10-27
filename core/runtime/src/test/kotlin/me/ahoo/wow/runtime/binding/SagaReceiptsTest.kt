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

package me.ahoo.wow.runtime.binding

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ExecutionReceiptContext
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decision D153: a command a saga derived in its own context that the kernel rejects is a business outcome. The wait
 * ends at the saga's hop with the rejection, nothing is retried for it, and the saga's `@OnRejected` handler for the
 * command compensates instead of the aggregate being parked; technical failures are still retried.
 */
class SagaReceiptsTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val decided = CopyOnWriteArrayList<String>()
    private val rejections = CopyOnWriteArrayList<CommandRejected<*>>()
    private val anyRejections = CopyOnWriteArrayList<CommandRejected<*>>()
    private val failures = AtomicInteger()

    @Volatile
    private var compensation: Any = AddItems(listOf("restored"))

    /** Puts the compensation back in the cart a rejected removal was for; fails [failures] times first. */
    private val onRemoveRejected = RejectionBinding.of(
        "CartSaga",
        "onRemoveRejected",
        RemoveItems::class.java,
    ) { rejected, context ->
        rejections += rejected
        check(failures.getAndDecrement() <= 0) { "The compensation could not be prepared." }
        listOf(CommandRequest.to(rejected.ref, compensation))
    }

    private val onAnyRejected = RejectionBinding.of<Any>("CartSaga", "onAnyRejected", null) { rejected, _ ->
        anyRejections += rejected
        emptyList()
    }

    private val commits = CopyOnWriteArrayList<CommandCommitted<*>>()

    /** Thanks the owner of the cart a removal was committed on (decision D192). */
    private val onRemoved = CommittedBinding.of(
        "CartSaga",
        "onRemoved",
        RemoveItems::class.java
    ) { committed, context ->
        commits += committed
        listOf(CommandRequest.to(committed.ref, AddItems(listOf("thanks"))))
    }

    private fun saga(vararg rejections: RejectionBinding<*>, commits: List<CommittedBinding<*>> = emptyList()) =
        ProcessorBinding.saga(
            "CartSaga",
            "onOrderPlaced",
            OrderState::class.java,
            rejections = rejections.toList(),
            commits = commits,
        ) { event, context ->
            val owner = event.scope.ownerId ?: return@saga emptyList()
            event.events.filterIsInstance<Placed>().map {
                CommandRequest.to(CART.ref(event.ref.tenantId, owner), RemoveItems(it.items))
            }
        }

    /** The saga that removes each item of an order on its own: one command per item, to the owner's cart. */
    private fun itemSaga(vararg rejections: RejectionBinding<*>) = ProcessorBinding.saga(
        "CartSaga",
        "onOrderPlaced",
        OrderState::class.java,
        rejections = rejections.toList(),
    ) { event, context ->
        val owner = event.scope.ownerId ?: return@saga emptyList()
        event.events.filterIsInstance<Placed>().flatMap { placed ->
            placed.items.map { CommandRequest.to(CART.ref(event.ref.tenantId, owner), RemoveItems(listOf(it))) }
        }
    }

    private var node: ServiceNode? = null

    private fun start(saga: ProcessorBinding<*>): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel(decided)),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga))),
    ).also {
        node = it
        runBlocking { it.start() }
    }

    @AfterEach
    fun stop(): Unit = runBlocking { node?.stop() ?: Unit }

    private fun request(aggregate: String, command: String, items: List<String>, id: String, wait: WaitPlan) =
        ServerCommandRequest(
            context = SHOP,
            aggregate = aggregate,
            command = command,
            body = CommandBody.of("""{"items":[${items.joinToString { "\"$it\"" }}]}"""),
            caller = Caller(TENANT, Scope(ownerId = "bob")),
            aggregateId = id,
            wait = wait,
        )

    private suspend fun ServiceNode.fill() {
        gateway.execute(request("cart", "AddItems", listOf("apple"), "bob", WaitPlan.COMMITTED))
            .status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    private suspend fun ServiceNode.place(id: String, vararg items: String): List<CommandResult> =
        gateway.send(request("order", "Place", items.toList(), id, CHAIN)).toList()

    private val cart = CART.ref(TENANT, "bob")
    private val ledger get() = backends.opened.getValue(SHOP).ledger
    private val log get() = backends.opened.getValue(SHOP).log

    private fun rejectedId(order: String) =
        ExecutionSagaContext.derivedId(ExecutionId(SAGA, ORDER.ref(TENANT, order), 1), cart, RemoveItems::class.java)

    private fun compensationId(
        order: String,
        handler: String = "CartSaga.onRemoveRejected",
        type: Class<*> = AddItems::class.java,
    ) = ExecutionReceiptContext.compensationId(
        ExecutionId(handler, ORDER.ref(TENANT, order), 1),
        rejectedId(order),
        cart,
        type
    )

    @Test
    fun `a rejection should go to the saga's handler, which compensates, and end the wait at the saga's hop`(): Unit =
        runBlocking {
            val node = start(saga(onRemoveRejected, onAnyRejected))
            node.fill()
            val all = node.place("o-1", FORBIDDEN_ITEM)
            val last = all.last()
            last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            last.hop.subscription.assert().isEqualTo(SAGA)
            last.error!!.code.assert().isEqualTo(FORBIDDEN)
            last.derived.assert().containsExactly(rejectedId("o-1"), compensationId("o-1"))
            // The handler of RemoveItems, not the one of every command, got it once: no retry.
            val rejected = rejections.single()
            anyRejections.assert().isEmpty()
            rejected.commandId.assert().isEqualTo(rejectedId("o-1"))
            rejected.commandType.assert().isEqualTo("shop.cart.RemoveItems")
            rejected.ref.assert().isEqualTo(cart)
            rejected.error.assert().isEqualTo(ErrorInfo(FORBIDDEN, "$FORBIDDEN_ITEM cannot be removed."))
            rejected.executionId.assert().isEqualTo(ExecutionId(SAGA, ORDER.ref(TENANT, "o-1"), 1))
            rejected.causation.causationId.assert().isEqualTo("shop.order/t/o-1@1")
            rejected.causation.correlationId.assert().isEqualTo(all.first().commandId)
            decided.assert().containsExactly(rejectedId("o-1"))
            // The compensation is committed, caused by the rejected command; the order is not parked.
            val compensated = log.findByRequestId(TENANT, compensationId("o-1"))!!
            compensated.ref.assert().isEqualTo(cart)
            compensated.causation.assert().isEqualTo(Causation(all.first().commandId, rejectedId("o-1")))
            ledger.isParked(SAGA, ORDER.ref(TENANT, "o-1")).assert().isFalse()
        }

    @Test
    fun `a command committed in the saga's context should go to its @OnCommitted handler, outside the wait`(): Unit =
        runBlocking {
            val node = start(saga(onRemoveRejected, commits = listOf(onRemoved)))
            node.fill()
            val all = node.place("o-c", "apple")
            val last = all.last()
            last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            last.hop.stage.assert().isEqualTo(Stage.COMMITTED)
            val committed = commits.single()
            committed.commandId.assert().isEqualTo(rejectedId("o-c"))
            committed.commandType.assert().isEqualTo("shop.cart.RemoveItems")
            committed.ref.assert().isEqualTo(cart)
            committed.version.assert().isEqualTo(2)
            committed.result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            committed.executionId.assert().isEqualTo(ExecutionId(SAGA, ORDER.ref(TENANT, "o-c"), 1))
            // What the handler derives is committed, caused by the committed command, but the wait does not follow it.
            val thanks = compensationId("o-c", "CartSaga.onRemoved")
            val derived = log.findByRequestId(TENANT, thanks)!!
            derived.causation.assert().isEqualTo(Causation(all.first().commandId, rejectedId("o-c")))
            all.flatMap { it.derived }.assert().doesNotContain(thanks)
            rejections.assert().isEmpty()
            // Handling the commit again hands the receipt over again; its command is not decided twice.
            val versions = log.read(cart).toList().size
            val subscription = SubscriptionBindings(mapOf(SHOP to listOf(saga(commits = listOf(onRemoved)))))
                .subscriptions(node.context(SHOP)).single()
            val again = subscription.handle(
                subscription.decoder.decode(log.read(ORDER.ref(TENANT, "o-c")).first(), null, null),
            )
            again.derived.assert().containsExactly(rejectedId("o-c"))
            commits.map { it.commandId }.distinct().assert().containsExactly(rejectedId("o-c"))
            commits.last().result.duplicate.assert().isTrue()
            log.read(cart).toList().size.assert().isEqualTo(versions)
        }

    @Test
    fun `handlers that leave the ref out should send to the aggregate the node's models assign`(): Unit = runBlocking {
        // Decision D234 ⑤: the saga, its compensation and its receipt handler name only the cart's id.
        val omitting = ProcessorBinding.saga(
            "CartSaga",
            "onOrderPlaced",
            OrderState::class.java,
            rejections = listOf(
                RejectionBinding.of("CartSaga", "onRemoveRejected", RemoveItems::class.java) { rejected, context ->
                    rejections += rejected
                    listOf(CommandRequest.of(AddItems(listOf("restored")), rejected.ref.id))
                },
            ),
            commits = listOf(
                CommittedBinding.of("CartSaga", "onRemoved", RemoveItems::class.java) { committed, context ->
                    commits += committed
                    listOf(CommandRequest.of(AddItems(listOf("thanks")), committed.ref.id))
                },
            ),
        ) { event, context ->
            val owner = event.scope.ownerId ?: return@saga emptyList()
            event.events.filterIsInstance<Placed>().map { CommandRequest.of(RemoveItems(it.items), owner) }
        }
        val node = start(omitting)
        node.fill()
        node.place("o-r", FORBIDDEN_ITEM).last().status.assert().isEqualTo(CommandResult.Status.REJECTED)
        rejections.single().ref.assert().isEqualTo(cart)
        log.findByRequestId(TENANT, compensationId("o-r"))!!.ref.assert().isEqualTo(cart)
        node.place("o-c", "apple").last().status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        commits.single().ref.assert().isEqualTo(cart)
        log.findByRequestId(TENANT, compensationId("o-c", "CartSaga.onRemoved"))!!.ref.assert().isEqualTo(cart)
    }

    @Test
    fun `a wait naming the @OnCommitted handler's hop should go on into the commands it derives`(): Unit = runBlocking {
        val node = start(saga(onRemoveRejected, commits = listOf(onRemoved)))
        node.fill()
        val chain = WaitPlan.of(
            Hop.committed(),
            Hop.handled(SAGA),
            Hop.committed(),
            Hop.handled(ON_REMOVED),
            Hop.committed()
        )
        val all = node.gateway.send(request("order", "Place", listOf("apple"), "o-h", chain)).toList()
        val thanks = compensationId("o-h", ON_REMOVED)
        val handled = all.single { it.hop == Hop.handled(ON_REMOVED, SHOP) }
        handled.commandId.assert().isEqualTo(rejectedId("o-h"))
        handled.derived.assert().containsExactly(thanks)
        val last = all.last()
        last.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        last.hop.assert().isEqualTo(Hop.committed(SHOP))
        last.commandId.assert().isEqualTo(thanks)
    }

    @Test
    fun `handling the same rejection again should derive the same compensation, which is not decided twice`(): Unit =
        runBlocking {
            val node = start(saga(onRemoveRejected))
            node.fill()
            node.place("o-2", FORBIDDEN_ITEM).last().status.assert().isEqualTo(CommandResult.Status.REJECTED)
            val versions = log.read(cart).toList().size

            // The saga's subscription handles the same entry again, as after a crash or an ownership change.
            val context = node.context(SHOP)
            val subscription = SubscriptionBindings(mapOf(SHOP to listOf(saga(onRemoveRejected))))
                .subscriptions(context).single()
            val entry = subscription.decoder.decode(log.read(ORDER.ref(TENANT, "o-2")).first(), null, null)
            val again = subscription.handle(entry)
            again.derived.assert().containsExactly(rejectedId("o-2"), compensationId("o-2"))
            again.rejection!!.code.assert().isEqualTo(FORBIDDEN)
            rejections.map { it.commandId }.distinct().assert().containsExactly(rejectedId("o-2"))
            log.read(cart).toList().size.assert().isEqualTo(versions)
        }

    @Test
    fun `a handler that fails should be retried, and a rejection none takes should park the aggregate`(): Unit =
        runBlocking {
            val node = start(saga(onRemoveRejected))
            node.fill()
            failures.set(1)
            node.place("o-3", FORBIDDEN_ITEM).last().status.assert().isEqualTo(CommandResult.Status.REJECTED)
            // A technical failure retries the handling: the saga derives the same command, rejected again.
            rejections.map { it.commandId }.assert().containsExactly(rejectedId("o-3"), rejectedId("o-3"))
            ledger.isParked(SAGA, ORDER.ref(TENANT, "o-3")).assert().isFalse()
            log.findByRequestId(TENANT, compensationId("o-3")).assert().isNotNull()

            // A compensation that is rejected in turn is not handed to a handler again: the order parks.
            compensation = RemoveItems(listOf(FORBIDDEN_ITEM))
            val last = node.place("o-4", FORBIDDEN_ITEM).last()
            last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            last.error!!.code.assert().isEqualTo(FORBIDDEN)
            eventually { ledger.isParked(SAGA, ORDER.ref(TENANT, "o-4")) }
            decided.assert().contains(compensationId("o-4", type = RemoveItems::class.java))
        }

    @Test
    fun `a saga with only a handler of every command should hand it the rejection`(): Unit = runBlocking {
        val node = start(saga(onAnyRejected))
        node.fill()
        val last = node.place("o-5", FORBIDDEN_ITEM).last()
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.derived.assert().containsExactly(rejectedId("o-5"))
        anyRejections.single().commandId.assert().isEqualTo(rejectedId("o-5"))
        ledger.isParked(SAGA, ORDER.ref(TENANT, "o-5")).assert().isFalse()
        // A removal that is not rejected goes on past the saga's hop to the COMMITTED one.
        node.place("o-6", "apple").last().hop.stage.assert().isEqualTo(Stage.COMMITTED)
    }

    @Test
    fun `each derived command should be settled on its own, the others committed and the rejection handled once`(): Unit =
        runBlocking {
            val node = start(itemSaga(onRemoveRejected))
            node.fill()
            node.gateway.execute(request("cart", "AddItems", listOf("pear"), "bob", WaitPlan.COMMITTED))
            val last = node.place("o-8", "apple", FORBIDDEN_ITEM, "pear").last()
            last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            // The removals of the first and the third item committed, after and despite the second's rejection.
            val removals = log.read(cart).toList().filter { it.commandType == "shop.cart.RemoveItems" }
            removals.flatMap { stream -> stream.events.map { it.payload } }.joinToString().assert()
                .contains("apple", "pear")
            removals.assert().hasSize(2)
            eventually { rejections.size == 1 }
            rejections.single().ref.assert().isEqualTo(cart)
            ledger.isParked(SAGA, ORDER.ref(TENANT, "o-8")).assert().isFalse()
        }

    @Test
    fun `rejections no handler takes should park once, listing every rejected command`(): Unit = runBlocking {
        val node = start(itemSaga())
        node.fill()
        node.place("o-9", "apple", FORBIDDEN_ITEM, "plum", FORBIDDEN_ITEM).last().status.assert()
            .isEqualTo(CommandResult.Status.REJECTED)
        eventually { ledger.isParked(SAGA, ORDER.ref(TENANT, "o-9")) }
        val parked = ledger.parked(SAGA, ORDER.ref(TENANT, "o-9"))!!
        parked.reason.assert().contains("2 derived commands were rejected", FORBIDDEN)
        // The removal before them committed; the one between them changed nothing, plum not being in the cart.
        log.read(cart).toList().count { it.commandType == "shop.cart.RemoveItems" }.assert().isEqualTo(1)
    }

    @Test
    fun `a rejection of no saga of the context, or no handler, should be left to the caller`(): Unit = runBlocking {
        val node = start(saga())
        val rejections = SagaReceipts(
            listOf(saga(onRemoveRejected)),
            DerivedCommands(node.context(SHOP).models, node.context(SHOP).coordinator),
        )
        val rejected = CommandRejected<Any>(
            "c-1",
            "shop.cart.RemoveItems",
            cart,
            ErrorInfo(FORBIDDEN, "No."),
            Causation("root", "shop.order/t/o-7@1"),
            ExecutionId("Other.saga", ORDER.ref(TENANT, "o-7"), 1),
        )
        rejections.rejected(rejected, RemoveItems::class.java).assert().isNull()
        rejections.rejected(
            rejected.copyWith(executionId = rejected.executionId.copy(subscription = SAGA)),
            String::class.java
        )
            .assert().isNull()
        // The entry point of a receipt from another context (decision D136) takes the same rejection.
        rejections.rejected(
            rejected.copyWith(executionId = rejected.executionId.copy(subscription = SAGA)),
            RemoveItems::class.java
        )
            .assert().hasSize(1)
    }

    private companion object {
        const val SAGA = "CartSaga.onOrderPlaced"
        const val ON_REMOVED = "CartSaga.onRemoved"
        val CHAIN: WaitPlan = WaitPlan.of(Hop.committed(), Hop.handled(SAGA), Hop.committed())
    }
}
