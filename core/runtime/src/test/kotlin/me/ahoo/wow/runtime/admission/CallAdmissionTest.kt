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
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.RequestFacts
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.result.WaitSignal
import me.ahoo.wow.kernel.Creation
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.runtime.Executor
import me.ahoo.wow.runtime.FrameworkCodes
import me.ahoo.wow.runtime.RecordingObserver
import me.ahoo.wow.runtime.wait.LocalSignalSender
import me.ahoo.wow.runtime.wait.SignalSender
import me.ahoo.wow.runtime.wait.SubscriptionDirectory
import me.ahoo.wow.runtime.wait.WaitCoordinator
import me.ahoo.wow.runtime.wait.WaitOptions
import me.ahoo.wow.runtime.wait.WaitRegistry
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.wait.Origin
import me.ahoo.wow.spi.wait.WaitContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * A service's synchronous call at admission (decision D193): its root command's tenant, scope, operator and causation
 * are taken from a service identity only, and the command is then a derived one (decision D199), executed with the
 * call's wait context and answered at COMMITTED.
 */
class CallAdmissionTest {
    data class AddItem(val sku: String)
    data class ItemAdded(val sku: String)

    class Cart

    private val commands = CopyOnWriteArrayList<Command<*>>()
    private val model = aggregate<Cart>(CONTEXT, "cart") {
        initial { Cart() }
        decide<AddItem>(Creation.ALLOWED) { _, body ->
            commands += command
            Decision.events(ItemAdded(body.sku))
        }
        evolve<ItemAdded> { }
    }
    private val origin = Origin("node-1", "127.0.0.1:9090", "shop")
    private val registry = WaitRegistry()
    private val log = InMemoryEventLog(partitions = 2)

    /** The signals sent to other nodes: the origins of the calls' waits. */
    private val remote = CopyOnWriteArrayList<WaitSignal>()
    private val coordinator = WaitCoordinator(
        Executor(Kernel(log), laneCapacity = 4),
        registry,
        SubscriptionDirectory.NONE,
        LocalSignalSender(origin.node, registry, SignalSender { _, signal -> remote += signal }),
        WaitOptions(origin, maxTimeout = 30.seconds),
    )

    private fun gateway(observer: Observer = Observer.NONE) = CommandGateway(
        ModelRegistry(listOf(model)),
        mapOf(CONTEXT to coordinator),
        committed = CommittedRequests.of(log),
        observer = observer,
    )

    private val service = Caller(subject = "service:basket", service = true)
    private val buyer = Caller(TENANT, Scope("mallory"), subject = "mallory")
    private val root = Causation("root-1", "basket/t/alice@2")

    private fun call(
        commandId: String = "call-1",
        owner: String = "alice",
        wait: WaitContext? = null,
        facts: Map<String, String> = emptyMap(),
    ) = CallContext(
        commandId = commandId,
        tenantId = TENANT,
        scope = Scope(owner),
        causation = root,
        operator = Operator(owner, TENANT),
        wait = wait,
        facts = facts,
    )

    private fun request(
        caller: Caller,
        call: CallContext?,
        aggregateId: String? = "cart-1",
        requestId: String = "request-1",
        target: TargetScope? = null,
        headers: Map<String, String> = emptyMap(),
    ) = CommandRequest(
        context = CONTEXT,
        aggregate = "cart",
        command = "AddItem",
        body = CommandBody.of("""{"sku":"mug"}"""),
        caller = caller,
        aggregateId = aggregateId,
        target = target,
        requestId = requestId,
        headers = headers,
        call = call,
    )

    @Test
    fun `a caller that is not a service identity should not assert a call`(): Unit = runBlocking {
        val result = gateway().execute(request(buyer, call()))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.AUTH_FORBIDDEN)
        // Its claimed command id is not taken either.
        result.commandId.assert().isNotEqualTo("call-1")
        commands.assert().isEmpty()
    }

    @Test
    fun `a call should name its scope in the call alone`(): Unit = runBlocking {
        val result = gateway().execute(request(service, call(), target = TargetScope(TENANT, Scope("bob"))))
        result.status.assert().isEqualTo(CommandResult.Status.REJECTED)
        result.error!!.code.assert().isEqualTo(FrameworkCodes.MALFORMED)
        commands.assert().isEmpty()
    }

    @Test
    fun `a service's call should be the root command's derived command, answered at COMMITTED`(): Unit = runBlocking {
        val result = gateway().execute(request(service, call()))
        result.status.assert().describedAs(result.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        result.hop.stage.assert().isEqualTo(Stage.COMMITTED)
        result.commandId.assert().isEqualTo("call-1")
        val command = commands.single()
        command.id.assert().isEqualTo("call-1")
        command.requestId.assert().isEqualTo("request-1")
        command.ref.tenantId.assert().isEqualTo(TENANT)
        command.scope.assert().isEqualTo(Scope("alice"))
        command.operator.assert().isEqualTo(Operator("alice", TENANT))
        command.causation.assert().isEqualTo(root)

        // A derived command is not checked against its scope on an existing target (decision D199): the root
        // command was authorized where it was admitted.
        val other = gateway().execute(request(service, call("call-2", owner = "bob"), requestId = "request-2"))
        other.status.assert().describedAs(other.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
    }

    @Test
    fun `a call should carry its root request's propagating facts, not the calling service's`(): Unit = runBlocking {
        // The edge recorded the calling service's own application; the call carries the root request's.
        val serviceFacts = mapOf(RequestFacts.APP_ID to "basket-service", "X-Trace" to "t-1")
        val result = gateway().execute(
            request(service, call(facts = mapOf(RequestFacts.APP_ID to "console")), headers = serviceFacts),
        )
        result.status.assert().describedAs(result.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        commands.single().headers.assert().isEqualTo(mapOf("X-Trace" to "t-1", RequestFacts.APP_ID to "console"))

        // A root request without an application leaves the call without one: never the calling service's.
        val bare = gateway().execute(request(service, call("call-2"), requestId = "request-2", headers = serviceFacts))
        bare.status.assert().describedAs(bare.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        commands.last().headers.assert().isEqualTo(mapOf("X-Trace" to "t-1"))
    }

    @Test
    fun `a call that creates without an id should keep its aggregate when it is called again`(): Unit = runBlocking {
        val first = gateway().execute(request(service, call(), aggregateId = null))
        first.status.assert().describedAs(first.toString()).isEqualTo(CommandResult.Status.SUCCEEDED)
        val again = gateway().execute(request(service, call(), aggregateId = null))
        again.ref.assert().isEqualTo(first.ref)
        again.duplicate.assert().isTrue()
        commands.assert().hasSize(1)
    }

    @Test
    fun `a call should signal the hops of the wait it carries to the wait's origin`(): Unit = runBlocking {
        val wait = WaitContext(
            "wait-1",
            Origin("basket-1", "127.0.0.1:9191", "basket"),
            listOf(Hop.committed(CONTEXT)),
            System.currentTimeMillis() + 60_000,
        )
        val observer = RecordingObserver()
        val result = gateway(observer).execute(request(service, call(wait = wait)))
        result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        val signal = remote.single()
        signal.waitId.assert().isEqualTo("wait-1")
        signal.result.commandId.assert().isEqualTo("call-1")
        signal.result.hop.assert().isEqualTo(Hop.committed(CONTEXT))
        // Admitted in a trace of its own, which its execution continues (decision D174).
        observer.seen.assert().containsExactly("admit <- null")
    }

    @Test
    fun `a call should name its command and its tenant`() {
        assertThrownBy<IllegalArgumentException> { call(commandId = " ") }
        assertThrownBy<IllegalArgumentException> { CallContext("c", " ", Scope.NONE, root) }
    }

    private companion object {
        const val CONTEXT = "shop"
        const val TENANT = "t1"
    }
}
