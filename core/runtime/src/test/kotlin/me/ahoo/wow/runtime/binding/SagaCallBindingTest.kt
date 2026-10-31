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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.problem.WowProblemException
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.api.subscribe.StateEvent
import me.ahoo.wow.kernel.CallRejectedHandlingException
import me.ahoo.wow.kernel.CommandCalls
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.CommittedBinding
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.RejectionBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.admission.CallContext
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ModelRegistry
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.call.SagaCalls
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.call.CommandCaller
import me.ahoo.wow.spi.call.OutgoingCall
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Delivers [items] to a mail box of the `mail` context, another service's. */
data class Deliver(val items: List<String>)
data class Delivered(val items: List<String>)

/**
 * A saga's synchronous calls in a node (decision D193): through the node's [CommandCaller], named by the peers'
 * Manifests, carrying the wait on; the saga's hop reports them first, a rejection parks at once, a failure is retried
 * then parked.
 */
class SagaCallBindingTest {
    private val backends = InMemoryBackends(partitions = 4)
    private val calls = CopyOnWriteArrayList<OutgoingCall>()

    /** How the mail context answers: committed by default. */
    @Volatile
    private var answer: suspend (OutgoingCall) -> CommandResult = { resultOf(it, CommandResult.Status.SUCCEEDED) }

    /** Calls the owner's mail box with the ordered items, then adds them to the owner's cart. */
    private suspend fun deliver(event: StateEvent<OrderState>, context: SagaContext): List<CommandRequest<*>> {
        val owner = checkNotNull(event.scope.ownerId)
        return event.events.filterIsInstance<Placed>().flatMap {
            context.call(CommandRequest.to(BOX.ref(event.ref.tenantId, owner), Deliver(it.items)))
            listOf(CommandRequest.to(CART.ref(event.ref.tenantId, owner), AddItems(it.items)))
        }
    }

    private val saga = ProcessorBinding.saga(
        "MailSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
        derives = listOf(AddItems::class.java),
        calls = listOf(Deliver::class.java),
        handle = ::deliver,
    )

    private val rejections = CopyOnWriteArrayList<CommandReceipt.Rejected<*>>()

    /** The mail saga with an `@OnRejected` handler of its delivery: a refused delivery leaves a note in the cart. */
    private val compensating = ProcessorBinding.saga(
        "MailSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
        rejections = listOf(
            RejectionBinding.of(
                "MailSaga",
                "onDeliverRejected",
                Deliver::class.java,
                derives = listOf(AddItems::class.java),
            ) { rejected, _ ->
                rejections += rejected
                listOf(
                    CommandRequest.to(CART.ref(rejected.ref.tenantId, rejected.ref.id), AddItems(listOf("undelivered")))
                )
            },
        ),
        derives = listOf(AddItems::class.java),
        calls = listOf(Deliver::class.java),
        handle = ::deliver,
    )

    /** Calls the owner's mail box without declaring the call. */
    private val undeclared = ProcessorBinding.saga(
        "MailSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
        derives = listOf(AddItems::class.java),
        handle = ::deliver,
    )

    /** Calls the mail box of the owner of the cart [receipt] is of, letting a rejection through. */
    private suspend fun mailOwner(receipt: CommandReceipt<*>, context: SagaContext): List<CommandRequest<*>> {
        context.call(CommandRequest.to(BOX.ref(receipt.ref.tenantId, receipt.ref.id), Deliver(listOf("receipt"))))
        return emptyList()
    }

    /** Sends [body] of the order's items to the owner's cart; its receipt handlers mail the owner. */
    private fun receiptMailing(
        body: (List<String>) -> Any,
        rejections: List<RejectionBinding<*>> = emptyList(),
        commits: List<CommittedBinding<*>> = emptyList(),
    ) = ProcessorBinding.saga(
        "MailSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
        rejections = rejections,
        commits = commits,
        derives = listOf(AddItems::class.java, RemoveItems::class.java),
    ) { event, _ ->
        val owner = checkNotNull(event.scope.ownerId)
        event.events.filterIsInstance<Placed>().map {
            CommandRequest.to(
                CART.ref(event.ref.tenantId, owner),
                body(it.items)
            )
        }
    }

    /** Adds the items; once the cart commits them, mails the owner. */
    private val mailingOnCommitted = receiptMailing(
        ::AddItems,
        commits = listOf(
            CommittedBinding.of("MailSaga", "onItemsAdded", AddItems::class.java, calls = listOf(Deliver::class.java)) {
                    committed, context ->
                mailOwner(committed, context)
            },
        ),
    )

    /** Removes the items from a cart that does not exist; once the cart rejects it, mails the owner. */
    private val mailingOnRejected = receiptMailing(
        ::RemoveItems,
        rejections = listOf(
            RejectionBinding.of(
                "MailSaga",
                "onRemoveRejected",
                RemoveItems::class.java,
                calls = listOf(Deliver::class.java)
            ) {
                    rejected, context ->
                mailOwner(rejected, context)
            },
        ),
    )

    private var other: ServiceNode? = null

    private val node = nodeOf(saga)

    private fun nodeOf(saga: ProcessorBinding<*>): ServiceNode = ServiceNode(
        SHOP_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(orderModel(), cartModel()),
        shopManifest(),
        backends,
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionBindings(mapOf(SHOP to listOf(saga))),
        contracts = ContractDirectory(shopManifest(), listOf(ManifestFragment("mail-service", mailManifest()))),
        caller = CommandCaller { call ->
            calls += call
            answer(call)
        },
    )

    @AfterEach
    fun stop(): Unit = runBlocking {
        node.stop()
        other?.stop()
    }

    private fun place(
        id: String,
        owner: String,
        wait: WaitPlan,
        headers: Map<String, String> = emptyMap(),
        on: ServiceNode = node,
    ) = on.gateway.send(
        ServerCommandRequest(
            context = SHOP,
            aggregate = "order",
            command = "Place",
            body = CommandBody.of("""{"items":["apple"]}"""),
            caller = Caller(TENANT, Scope(ownerId = owner)),
            aggregateId = id,
            headers = headers,
            wait = wait,
        ),
    )

    private val toSaga = WaitPlan.of(Hop.committed(), Hop.handled(saga.name))

    @Test
    fun `a saga's call should go first among what its hop reports, and carry the wait on`(): Unit = runBlocking {
        node.start()
        val chain = WaitPlan(listOf(Hop.committed(), Hop.handled(saga.name), Hop.committed("mail")), 2.seconds)
        val all = place("o-1", "alice", chain).toList()
        val handled = all.first { it.hop.stage == Stage.HANDLED }
        handled.status.assert().describedAs(all.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        val execution = ExecutionId(saga.name, ORDER.ref(TENANT, "o-1"), 1)
        handled.derived.assert().containsExactly(
            ExecutionSagaContext.derivedId(execution, BOX.ref(TENANT, "alice"), Deliver::class.java),
            ExecutionSagaContext.derivedId(execution, CART.ref(TENANT, "alice"), AddItems::class.java),
        )
        val call = calls.single()
        call.type.assert().isEqualTo("mail.box.Deliver")
        call.name.assert().isEqualTo("Deliver")
        call.command.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        // The rest of the chain, after the saga's hop, goes with the call to the mail context.
        call.wait!!.remaining.assert().containsExactly(Hop.committed("mail"))
    }

    @Test
    fun `a saga's call should carry the root request's application to the command it runs (D236)`(): Unit =
        runBlocking {
            val delivered = CopyOnWriteArrayList<Command<*>>()
            val mail = mailGateway(delivered)
            val service = Caller(subject = "service:shop-service", service = true)
            // The mail context's gateway takes the call as its internal port does (HttpCommandCaller, RequestHeaders):
            // the facts the edge recorded are the calling service's own, its Wow-Call the command's inherited ones.
            answer = { call ->
                val command = call.command
                mail.execute(
                    ServerCommandRequest(
                        context = "mail",
                        aggregate = "box",
                        command = call.name,
                        body = CommandBody.of((command.body as Deliver).items.joinToString(",", "{\"items\":[", "]}") { "\"$it\"" }),
                        caller = service,
                        aggregateId = command.ref.id,
                        requestId = command.requestId,
                        headers = mapOf(RequestFacts.APP_ID to "shop-service-app"),
                        call = CallContext.of(
                            command.id,
                            command.ref.tenantId,
                            command.scope,
                            checkNotNull(command.causation),
                            command.operator,
                            facts = command.headers,
                        ),
                    ),
                )
            }
            node.start()
            val root = mapOf(RequestFacts.APP_ID to "console", RequestFacts.REMOTE_IP to "203.0.113.9")
            val handled = place("o-9", "dave", toSaga, root).toList().last()
            handled.status.assert().describedAs(handled.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
            calls.single().command.headers.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
            delivered.single().headers.assert().isEqualTo(mapOf(RequestFacts.APP_ID to "console"))
        }

    @Test
    fun `a call the target rejects should park the order at once and end the wait at the saga's hop`(): Unit =
        runBlocking {
            answer = { resultOf(it, CommandResult.Status.REJECTED) }
            node.start()
            val last = place("o-2", "bob", toSaga).toList().last()
            last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            last.error!!.code.assert().isEqualTo("mail.box.full")
            val ledger = backends.opened.getValue(SHOP).ledger
            eventually { ledger.isParked(saga.name, ORDER.ref(TENANT, "o-2")) }
            // A rejection is not retried.
            calls.assert().hasSize(1)
        }

    @Test
    fun `a call's rejection the saga lets through should go to its @OnRejected handler, the handling deriving nothing`():
        Unit = runBlocking {
        // D372 S10: as a derived command's rejection does, instead of parking the order.
        answer = { resultOf(it, CommandResult.Status.REJECTED) }
        val node = nodeOf(compensating).also { other = it }
        node.start()
        val last = place("o-6", "erin", toSaga, on = node).toList().last()
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.error!!.code.assert().isEqualTo("mail.box.full")
        val rejected = rejections.single()
        rejected.commandType.assert().isEqualTo("mail.box.Deliver")
        rejected.ref.assert().isEqualTo(BOX.ref(TENANT, "erin"))
        rejected.source.assert().isEqualTo(ORDER.ref(TENANT, "o-6"))
        rejected.error.code.assert().isEqualTo("mail.box.full")
        // The aborted handling added nothing to the cart: only the compensation did.
        val log = backends.opened.getValue(SHOP).log
        eventually { log.read(CART.ref(TENANT, "erin")).toList().isNotEmpty() }
        log.read(CART.ref(TENANT, "erin")).toList().flatMap { it.events }.map { it.payload }
            .assert().containsExactly("""{"items":["undelivered"]}""")
        backends.opened.getValue(
            SHOP
        ).ledger.isParked(compensating.name, ORDER.ref(TENANT, "o-6")).assert().isFalse()
        calls.assert().hasSize(1)
    }

    @Test
    fun `a call's rejection an @OnCommitted handler lets through should park the order at once, not be retried`():
        Unit = runBlocking {
        answer = { resultOf(it, CommandResult.Status.REJECTED) }
        val node = nodeOf(mailingOnCommitted).also { other = it }
        node.start()
        val last = place("o-c", "gina", toSaga, on = node).toList().last()
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.error!!.code.assert().isEqualTo("mail.box.full")
        val ledger = backends.opened.getValue(SHOP).ledger
        eventually { ledger.isParked(mailingOnCommitted.name, ORDER.ref(TENANT, "o-c")) }
        // As a rejection of a command the handler derives: a business outcome, which no retry changes.
        calls.assert().hasSize(1)
    }

    @Test
    fun `a call's rejection an @OnRejected handler lets through should park the order at once, not be retried`():
        Unit = runBlocking {
        answer = { resultOf(it, CommandResult.Status.REJECTED) }
        val node = nodeOf(mailingOnRejected).also { other = it }
        node.start()
        val last = place("o-r", "hank", toSaga, on = node).toList().last()
        last.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        last.error!!.code.assert().isEqualTo("mail.box.full")
        val ledger = backends.opened.getValue(SHOP).ledger
        eventually { ledger.isParked(mailingOnRejected.name, ORDER.ref(TENANT, "o-r")) }
        // A compensation's rejection goes to no @OnRejected handler again.
        calls.assert().hasSize(1)
    }

    @Test
    fun `an @OnCommitted handler's hop should end REJECTED with the call's rejection it lets through`(): Unit =
        runBlocking {
            answer = { resultOf(it, CommandResult.Status.REJECTED) }
            node.start()
            val signals = CopyOnWriteArrayList<CommandResult>()
            val shop = node.context(SHOP)
            val receipts = SagaReceipts(
                listOf(mailingOnCommitted),
                DerivedCommands(shop.models, shop.coordinator),
                context = SHOP,
                signal = { _, result -> signals += result },
                commandTypes = shop.commandTypes,
                caller = shop.caller,
            )
            val cart = CART.ref(TENANT, "ivy")
            val execution = ExecutionId(mailingOnCommitted.name, ORDER.ref(TENANT, "o-w"), 1)
            val committed = CommandReceipt.Committed<Any>(
                "c-1",
                "shop.cart.AddItems",
                cart,
                1,
                CommandResult(
                    commandId = "c-1",
                    requestId = "c-1",
                    correlationId = "root",
                    ref = cart,
                    hop = Hop.committed(SHOP),
                    function = FunctionInfo(FunctionKind.COMMAND, SHOP, "cart", "AddItems"),
                    status = CommandResult.Status.SUCCEEDED,
                    version = 1,
                    signalTime = 1L,
                ),
                Causation("root", "shop.order/t/o-w@1"),
                execution,
            )
            val onItemsAdded = Hop.handled("MailSaga.onItemsAdded", SHOP)
            val wait = WaitContext("w", Origin("n", "a:1", "s"), listOf(onItemsAdded), Long.MAX_VALUE)
            runCatching { receipts.committed(committed, AddItems::class.java, wait) }.exceptionOrNull()
                .assert().isInstanceOf(CallRejectedHandlingException::class.java)
            val signalled = signals.single()
            signalled.hop.assert().isEqualTo(onItemsAdded)
            signalled.status.assert().isEqualTo(CommandResult.Status.REJECTED)
            signalled.error!!.code.assert().isEqualTo("mail.box.full")
            signalled.derived.assert().containsExactly(calls.single().command.id)
        }

    @Test
    fun `a call the saga does not declare should park the order at once, never reaching its target`(): Unit =
        runBlocking {
            // D372 S12: a bug in the saga, which no retry fixes.
            val node = nodeOf(undeclared).also { other = it }
            node.start()
            val last = place("o-7", "frank", toSaga, on = node).toList().last()
            last.status.assert().isEqualTo(CommandResult.Status.FAILED)
            val ledger = backends.opened.getValue(SHOP).ledger
            eventually { ledger.isParked(undeclared.name, ORDER.ref(TENANT, "o-7")) }
            ledger.parked(undeclared.name, ORDER.ref(TENANT, "o-7"))!!.reason
                .assert().contains("which its @Sends(calls) does not declare")
            calls.assert().isEmpty()
        }

    @Test
    fun `a call that fails should be retried within the saga's budget, then park the order`(): Unit = runBlocking {
        answer = { throw CommandCalls.failure("The mail service is down.") }
        node.start()
        val last = place("o-3", "carol", toSaga).toList().last()
        last.status.assert().isEqualTo(CommandResult.Status.FAILED)
        val ledger = backends.opened.getValue(SHOP).ledger
        eventually { ledger.isParked(saga.name, ORDER.ref(TENANT, "o-3")) }
        ledger.parked(saga.name, ORDER.ref(TENANT, "o-3"))!!.reason.assert().contains("The mail service is down.")
        // The budget's two attempts, each the same request.
        calls.assert().hasSize(2)
        calls.map { it.command.requestId }.distinct().assert().hasSize(1)
    }

    @Test
    fun `the calls of an execution should go only to other contexts, named by their Manifests`(): Unit = runBlocking {
        val recorded = CopyOnWriteArrayList<OutgoingCall>()
        val names = CommandTypes { target, command -> "$target.${command.simpleName}".takeIf { target == BOX } }
        val calls = SagaCalls(
            { call ->
                recorded += call
                resultOf(call, CommandResult.Status.FAILED)
            },
            setOf(SHOP),
            names,
            null,
        )
        // A command of the node's own context is derived, not called.
        assertThrownBy<IllegalArgumentException> {
            runBlocking { calls.call(Command("c", "r", CART.ref(TENANT, "alice"), RemoveItems(emptyList()))) }
        }
        // A command no Manifest names cannot be called.
        assertThrownBy<IllegalArgumentException> {
            runBlocking { calls.call(Command("c", "r", AggregateType("mail", "other").ref(TENANT, "a"), "x")) }
        }
        // A call carries its chain; a failed one is not reported.
        val root = Causation("root", "shop.order/$TENANT/o-1@1")
        val failed = calls.call(Command("c-1", "r-1", BOX.ref(TENANT, "alice"), Deliver(emptyList()), causation = root))
        failed.status.assert().isEqualTo(CommandResult.Status.FAILED)
        recorded.single().command.causation.assert().isEqualTo(root)
        calls.called.assert().isEmpty()
        // Without a client, a call fails.
        val none = SagaCalls(null, setOf(SHOP), names, null)
        assertThrownBy<WowProblemException> {
            runBlocking { none.call(Command("c", "r", BOX.ref(TENANT, "alice"), Deliver(emptyList()))) }
        }
    }

    @Test
    fun `an outgoing call should be a derived command of its type`() {
        val command = Command("c", "r", BOX.ref(TENANT, "alice"), Deliver(emptyList()), causation = Causation("a", "b"))
        val wait = WaitContext("w", Origin("n", "a:1", "s"), listOf(Hop.committed("mail")), 1L)
        OutgoingCall("mail.box.Deliver", command, wait).name.assert().isEqualTo("Deliver")
        assertThrownBy<IllegalArgumentException> { OutgoingCall("mail.other.Deliver", command) }
        assertThrownBy<IllegalArgumentException> {
            OutgoingCall(
                "mail.box.Deliver",
                command.copyWith(causation = null)
            )
        }
    }

    private companion object {
        val BOX = AggregateType("mail", "box")

        class Box

        /** The mail context's gateway on a node of its own: its box takes [Deliver], recorded in [delivered]. */
        fun mailGateway(delivered: MutableList<Command<*>>): CommandGateway {
            val model = aggregate<Box>("mail", "box") {
                initial { Box() }
                decide<Deliver>(Creation.ALLOWED) { _, body ->
                    delivered += command
                    Decision.events(Delivered(body.items))
                }
                evolve<Delivered> { }
            }
            val registry = WaitRegistry()
            val coordinator = WaitCoordinator(
                Executor(Kernel(InMemoryEventLog(partitions = 2))),
                registry,
                SubscriptionDirectory.NONE,
                LocalSignalSender("mail-node-1", registry),
                Origin("mail-node-1", "127.0.0.1:9191", "mail-service"),
                WaitOptions(maxTimeout = 5.seconds),
            )
            return CommandGateway(ModelRegistry(listOf(model)), mapOf("mail" to coordinator))
        }

        fun resultOf(call: OutgoingCall, status: CommandResult.Status) = CommandResult(
            commandId = call.command.id,
            requestId = call.command.requestId,
            correlationId = call.command.causation!!.correlationId,
            ref = call.command.ref,
            hop = Hop.committed("mail"),
            function = FunctionInfo(FunctionKind.COMMAND, "mail", "box", call.name),
            status = status,
            version = 1,
            error = if (status == CommandResult.Status.SUCCEEDED) null else ErrorInfo("mail.box.full", "Full."),
            signalTime = 1L,
        )

        /** The mail service's Manifest, a peer of the shop's: its box takes [Deliver]. */
        fun mailManifest(): Manifest = Manifest(
            service = ServiceInfo("mail-service"),
            contexts = listOf(
                ContextManifest(
                    "mail",
                    aggregates = listOf(
                        AggregateManifest(
                            "box",
                            state = StateManifest(Manifest.schemaRef("BoxState")),
                            commands = listOf(
                                CommandManifest("Deliver", schema = Manifest.schemaRef(Deliver::class.java.name)),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
