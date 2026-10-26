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

package me.ahoo.wow.runtime

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.runtime.admission.CommandBody
import me.ahoo.wow.runtime.admission.CommandRequest
import me.ahoo.wow.runtime.cluster.ClusterCodec
import me.ahoo.wow.runtime.cluster.Deposit
import me.ahoo.wow.runtime.cluster.ForwardCommand
import me.ahoo.wow.runtime.cluster.ForwardEndpoint
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.NODE_SERVICE
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.SubscriptionFactory
import me.ahoo.wow.runtime.node.accountModel
import me.ahoo.wow.runtime.node.accountsManifest
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.runtime.subscription.SubscriptionFilter
import me.ahoo.wow.runtime.subscription.eventually
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Transport
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.observation.TraceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * The trace of a test: [name], the thread's [CURRENT] while a coroutine that carries it runs, as an OpenTelemetry
 * context would be; its propagation fields name it too.
 */
class TestTrace(val name: String) :
    TraceContext(),
    ThreadContextElement<String?> {
    override val fields: Map<String, String> = mapOf(TRACEPARENT to name)

    override fun updateThreadContext(context: CoroutineContext): String? = CURRENT.get().also { CURRENT.set(name) }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) = CURRENT.set(oldState)

    companion object {
        const val TRACEPARENT = "traceparent"
        val CURRENT: ThreadLocal<String?> = ThreadLocal()
    }
}

/**
 * Records each stage the core observes, with the trace it starts in (the current [TestTrace], or the propagation
 * fields it is handed), and runs it in a [TestTrace] named after the stage.
 */
class RecordingObserver : Observer {
    val seen = CopyOnWriteArrayList<String>()

    private fun stage(stage: String, from: String?): Observation {
        seen += "$stage <- $from"
        return object : Observation {
            override val context: CoroutineContext = TestTrace(stage)
        }
    }

    override fun admitting(context: String, aggregate: String, command: String, trace: Map<String, String>) =
        stage("admit", trace[TestTrace.TRACEPARENT])

    override fun executing(command: Command<*>) = stage("execute", TestTrace.CURRENT.get())

    override fun deciding(command: Command<*>) = stage("decide", TestTrace.CURRENT.get())

    override fun appending(stream: EventStreamRecord) = stage("append", TestTrace.CURRENT.get())

    override fun handling(
        subscription: String,
        context: String,
        stream: EventStreamRecord,
        trace: Map<String, String>,
        topic: String?,
    ) = stage("handle $subscription", trace[TestTrace.TRACEPARENT])

    override fun resuming(trace: Map<String, String>): CoroutineContext =
        TestTrace("resumed ${trace[TestTrace.TRACEPARENT]}")
}

/** A node's work observed (decision D174): each stage in the trace the one before it started. */
class ObservedNodeTest {
    private val observer = RecordingObserver()
    private val audited = CopyOnWriteArrayList<String>()
    private val node = ServiceNode(
        NODE_SERVICE,
        "node-1",
        "node-1.internal:9090",
        listOf(accountModel(BANK)),
        accountsManifest(BANK),
        InMemoryBackends(),
        LoopbackClusterChannel(),
        options = fastNodeOptions(),
        subscriptions = SubscriptionFactory {
            listOf(Subscription("audit", SubscriptionFilter(BANK)) { audited += it.ref.id })
        },
        observer = observer,
    )

    @AfterEach
    fun stop(): Unit = runBlocking {
        node.stop()
    }

    private fun deposit(id: String, wait: WaitPlan? = null) = CommandRequest(
        BANK,
        "account",
        "Deposit",
        CommandBody.of("""{"amount":1}"""),
        aggregateId = id,
        wait = wait,
        trace = mapOf(TestTrace.TRACEPARENT to "caller"),
    )

    @Test
    fun `an admitted command should execute in its admission's trace, and its entry carry its append's`(): Unit =
        runBlocking {
            node.start()
            node.gateway.execute(deposit("a-1")).status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            eventually { "a-1" in audited }
            observer.seen.assert().contains(
                "admit <- caller",
                "execute <- admit",
                "decide <- execute",
                "append <- execute",
                "handle audit <- append",
            )
            val ref = AggregateType(BANK, "account").ref(TENANT, "a-1")
            val entry = node.context(BANK).backends.log.readEntries(ref).toList().single()
            entry.decodedTransport!!.trace.assert().isEqualTo(mapOf(TestTrace.TRACEPARENT to "append"))
        }

    @Test
    fun `a command answered at once should execute in its admission's trace too`(): Unit = runBlocking {
        node.start()
        node.gateway.execute(deposit("a-2", WaitPlan.ACCEPTED)).status.assert()
            .isEqualTo(CommandResult.Status.SUCCEEDED)
        eventually { "a-2" in audited }
        observer.seen.assert().contains("execute <- admit")
    }

    @Test
    fun `a forwarded command should execute in the trace it was forwarded with`(): Unit = runBlocking {
        val context = node.context(BANK)
        val model = context.models.first()
        val endpoint = ForwardEndpoint(context.coordinator, listOf(model), emptyList(), observer = observer)
        val ref = model.type.ref(TENANT, "a-3")
        val forwarded = ClusterCodec.wire(model, Command("c-3", "r-3", ref, Deposit()))
        val deadline = System.currentTimeMillis() + 10_000
        val peer = mapOf(TestTrace.TRACEPARENT to "peer")
        endpoint.forward(ForwardCommand(command = forwarded, transport = Transport(trace = peer), deadline = deadline))
            .result!!.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        // One forwarded without a trace starts none.
        val untraced = ClusterCodec.wire(model, Command("c-4", "r-4", model.type.ref(TENANT, "a-4"), Deposit()))
        endpoint.forward(ForwardCommand(command = untraced, deadline = deadline))
            .result!!.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
        observer.seen.filter { it.startsWith("execute") }.assert()
            .containsExactly("execute <- resumed peer", "execute <- resumed null")
    }

    private companion object {
        const val BANK = "bank"
        const val TENANT = "(0)"
    }
}
