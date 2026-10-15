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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.ErrorInfo
import me.ahoo.wow.api.result.FunctionInfo
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.api.subscribe.CallFailedException
import me.ahoo.wow.api.subscribe.ExecutionId
import me.ahoo.wow.kernel.CommandTypes
import me.ahoo.wow.kernel.ExecutionSagaContext
import me.ahoo.wow.kernel.HandlerRetry
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.call.CommandCaller
import me.ahoo.wow.runtime.call.OutgoingCall
import me.ahoo.wow.runtime.call.SagaCalls
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** Delivers [items] to a mail box of the `mail` context, another service's. */
data class Deliver(val items: List<String>)

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
    private var answer: (OutgoingCall) -> CommandResult = { resultOf(it, CommandResult.Status.SUCCEEDED) }

    /** Calls the owner's mail box with the ordered items, then adds them to the owner's cart. */
    private val saga = ProcessorBinding.saga(
        "MailSaga",
        "onOrderPlaced",
        OrderState::class.java,
        retry = HandlerRetry(maxAttempts = 2),
    ) { event, context ->
        val owner = checkNotNull(event.scope.ownerId)
        event.events.filterIsInstance<Placed>().flatMap {
            context.call(context.command(BOX.ref(event.ref.tenantId, owner), Deliver(it.items)))
            listOf(context.command(CART.ref(event.ref.tenantId, owner), AddItems(it.items)))
        }
    }

    private val node = ServiceNode(
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
    fun stop(): Unit = runBlocking { node.stop() }

    private fun place(id: String, owner: String, wait: WaitPlan) = node.gateway.send(
        CommandRequest(
            context = SHOP,
            aggregate = "order",
            command = "Place",
            body = CommandBody.of("""{"items":["apple"]}"""),
            caller = Caller(TENANT, Scope(ownerId = owner)),
            aggregateId = id,
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
            ExecutionSagaContext.derivedId(execution, 0),
            ExecutionSagaContext.derivedId(execution, 1),
        )
        val call = calls.single()
        call.type.assert().isEqualTo("mail.box.Deliver")
        call.name.assert().isEqualTo("Deliver")
        call.command.scope.assert().isEqualTo(Scope(ownerId = "alice"))
        // The rest of the chain, after the saga's hop, goes with the call to the mail context.
        call.wait!!.remaining.assert().containsExactly(Hop.committed("mail"))
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
    fun `a call that fails should be retried within the saga's budget, then park the order`(): Unit = runBlocking {
        answer = { throw CallFailedException("The mail service is down.") }
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
        // One without a causation is its own root; a failed one is not reported.
        val failed = calls.call(Command("c-1", "r-1", BOX.ref(TENANT, "alice"), Deliver(emptyList())))
        failed.status.assert().isEqualTo(CommandResult.Status.FAILED)
        recorded.single().command.causation.assert().isEqualTo(Causation("c-1", "c-1"))
        calls.called.assert().isEmpty()
        // Without a client, a call fails.
        val none = SagaCalls(null, setOf(SHOP), names, null)
        assertThrownBy<CallFailedException> {
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
