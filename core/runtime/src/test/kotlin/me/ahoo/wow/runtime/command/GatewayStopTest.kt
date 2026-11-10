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

package me.ahoo.wow.runtime.command

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.api.result.Hop
import me.ahoo.wow.api.result.WaitPlan
import me.ahoo.wow.kernel.memory.InMemoryTopics
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.CommandGateway
import me.ahoo.wow.runtime.admission.ServerCommandRequest
import me.ahoo.wow.runtime.cluster.LoopbackClusterChannel
import me.ahoo.wow.runtime.cluster.fastOptions
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.node.AdmissionPlugins
import me.ahoo.wow.runtime.node.InMemoryBackends
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.runtime.node.ServiceNodeOptions
import me.ahoo.wow.runtime.node.SourceTopics
import me.ahoo.wow.runtime.node.fastNodeOptions
import me.ahoo.wow.spi.tenant.TenantPolicy
import me.ahoo.wow.spi.transport.TopicMessage
import me.ahoo.wow.spi.transport.TopicProducer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The gateway's close at a node's stop (GATEWAY_CLOSED): it waits for a peer context's command accepted in process,
 * its plan ACCEPTED only, as for every other command it accepted; and it waits at most the node's drain timeout for
 * what nothing else bounds (an admission's plugins, a detached command's run), logging and cancelling what is left.
 */
class GatewayStopTest {
    private val topics = InMemoryTopics(2).also { it.create(COMMANDS, PARTITIONS) }
    private val channel = LoopbackClusterChannel()
    private val nodes = CopyOnWriteArrayList<ServiceNode>()
    private val stock = StockItems().apply { broken = false }
    private val clerk = Caller(TENANT, Scope("clerk"), subject = "clerk")

    @AfterEach
    fun stop(): Unit = runBlocking {
        nodes.forEach { it.stop() }
    }

    private fun stockNode(
        options: ServiceNodeOptions = fastNodeOptions(),
        authorizer: CommandAuthorizer? = null,
        intake: Boolean = true,
    ): ServiceNode = ServiceNode(
        STOCK_SERVICE,
        "stock-1",
        "stock-1.internal:9090",
        listOf(stock.model),
        stockManifest(),
        InMemoryBackends(PARTITIONS),
        channel,
        options = options,
        admission = AdmissionPlugins(authorizer = authorizer, tenants = TenantPolicy.CALLERS),
        topics = if (intake) SourceTopics(topics, PREFIX, topics, intake = true) else null,
    ).also { register(it) }

    /** Options whose drain timeout, the gateway's bound at the node's stop, is short. */
    private fun shortDrain(): ServiceNodeOptions = fastNodeOptions().copyWith(
        cluster = fastOptions(drainTimeout = DRAIN),
    )

    /** A peer's stock reservation, ACCEPTED only. */
    private fun request() = ServerCommandRequest.builder(Reserve(2), ITEM.ref(TENANT, "i-1"))
        .caller(clerk)
        .wait(WaitPlan.ACCEPTED)
        .build()

    private fun reserve(item: String) = ServerCommandRequest.builder(Reserve(1), ITEM.ref(TENANT, item))
        .caller(clerk)
        .wait(WaitPlan.ACCEPTED)
        .build()

    /** What the gateway warns of while [block] runs. */
    private suspend fun gatewayWarnings(block: suspend () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(CommandGateway::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
    }

    private fun salesNode(
        producer: TopicProducer,
        options: ServiceNodeOptions = fastNodeOptions(),
        authorizer: CommandAuthorizer? = null,
        listener: (ServiceNode.Step) -> Unit,
    ): ServiceNode = ServiceNode(
        SALES_SERVICE,
        "sales-1",
        "sales-1.internal:9090",
        listOf(orderModel()),
        salesManifest(),
        InMemoryBackends(PARTITIONS),
        channel,
        options = options,
        admission = AdmissionPlugins(authorizer = authorizer, tenants = TenantPolicy.CALLERS),
        contracts = ContractDirectory(salesManifest(), listOf(peer(stockManifest()))),
        topics = SourceTopics(topics, PREFIX, producer),
        listener = listener,
    ).also { register(it) }

    private fun register(node: ServiceNode) {
        nodes += node
        channel.bind(node.address, node.forwardHandler)
    }

    @Test
    fun `a peer's command accepted in process should hold the gateway's close until the command topic took it`(): Unit =
        runBlocking {
            stockNode().start()
            val sending = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            // Its send to the stock's command topic waits until the stop is about to close the gateway.
            val gated = object : TopicProducer {
                override suspend fun send(topic: String, messages: List<TopicMessage>) {
                    if (topic == COMMANDS) {
                        sending.complete(Unit)
                        released.await()
                    }
                    topics.send(topic, messages)
                }
            }
            val answered = CompletableDeferred<CommandResult>()
            val answeredAtGatewayClosed = CompletableDeferred<Boolean>()
            val sales = salesNode(gated) { step ->
                when (step) {
                    ServiceNode.Step.WAITS_ENDED -> released.complete(Unit)
                    ServiceNode.Step.GATEWAY_CLOSED -> answeredAtGatewayClosed.complete(answered.isCompleted)
                    else -> Unit
                }
            }.also { it.start() }
            // On the test's one thread, as the stop is: the relay resumes only when the stop suspends, so the gateway's
            // close can only see it answered by waiting for it.
            val calling = async { answered.complete(sales.gateway.execute(request())) }
            sending.await()
            sales.stop()
            nodes -= sales
            calling.await()
            // The gateway closed only once the command topic took the command and the caller was answered.
            answeredAtGatewayClosed.await().assert().isTrue()
            val result = answered.await()
            result.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
            result.hop.assert().isEqualTo(Hop.accepted(STOCK))
            topics.messages(COMMANDS).size.assert().isEqualTo(1)
        }

    /**
     * An authorizer that holds each admission until [released]: past the gateway's bound, as the application's plugin
     * may, since nothing of the gateway's bounds it. [authorizing] once one waits.
     */
    private class HeldAuthorizer : CommandAuthorizer {
        val authorizing = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()

        override suspend fun authorize(caller: Caller, action: CommandAction): Authorization {
            authorizing.complete(Unit)
            released.await()
            return Authorization.Allowed
        }
    }

    /**
     * Stops [node] while [held] holds an admission past the gateway's bound, then releases it: the warning the gateway
     * logs, and what [calling]'s caller is answered.
     */
    private suspend fun stopPastAdmission(
        node: ServiceNode,
        held: HeldAuthorizer,
        calling: Deferred<CommandResult>,
    ): Pair<List<String>, CommandResult> {
        try {
            held.authorizing.await()
            val warned = gatewayWarnings { withTimeout(STOPPED_WITHIN) { node.stop() } }
            nodes -= node
            held.released.complete(Unit)
            return warned to withTimeout(STOPPED_WITHIN) { calling.await() }
        } finally {
            held.released.complete(Unit)
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `an admission that outlives the gateway's bound should be refused as closing, its command never run`(): Unit =
        runBlocking {
            val held = HeldAuthorizer()
            val node = stockNode(shortDrain(), authorizer = held, intake = false).also { it.start() }
            val calling = async { node.gateway.execute(reserve("i-a")) }
            val (warned, answered) = stopPastAdmission(node, held, calling)
            warned.assert().containsExactly(ADMISSION_OUTLIVED)
            answered.status.assert().isEqualTo(CommandResult.Status.FAILED)
            answered.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
            stock.decided.assert().isEmpty()
        }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a peer's command whose admission outlives the gateway's bound should be refused as closing, never sent`():
        Unit = runBlocking {
        stockNode().start()
        val held = HeldAuthorizer()
        val sales = salesNode(topics, shortDrain(), held) { }.also { it.start() }
        val calling = async { sales.gateway.execute(request()) }
        val (warned, answered) = stopPastAdmission(sales, held, calling)
        warned.assert().containsExactly(ADMISSION_OUTLIVED)
        answered.status.assert().isEqualTo(CommandResult.Status.FAILED)
        answered.error!!.code.assert().isEqualTo(FrameworkCodes.NODE_CLOSING)
        topics.messages(COMMANDS).assert().isEmpty()
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a detached command that never ends should hold the gateway's close at most the drain timeout, logged`(): Unit =
        runBlocking {
            // Accepted at once, it runs detached, its decision held for good.
            val held = CompletableDeferred<Unit>().also { stock.gates["i-d"] = it }
            try {
                val node = stockNode(shortDrain(), intake = false).also { it.start() }
                val accepted = node.gateway.execute(reserve("i-d"))
                accepted.status.assert().isEqualTo(CommandResult.Status.SUCCEEDED)
                val warned = gatewayWarnings { withTimeout(STOPPED_WITHIN) { node.stop() } }
                nodes -= node
                warned.assert().containsExactly(
                    "The gateway closes after $DRAIN: 0 admissions under way are refused when they get past " +
                        "admission, 1 detached commands running are cancelled.",
                )
                stock.decided.assert().doesNotContain(accepted.commandId)
            } finally {
                // Let a node the test failed to stop stop after it.
                held.complete(Unit)
            }
        }

    private companion object {
        const val PARTITIONS = 4
        const val COMMANDS = PREFIX + "wow.stock.commands"
        val DRAIN = 200.milliseconds

        /** The gateway's warning for one admission held past its bound, and nothing detached. */
        val ADMISSION_OUTLIVED = "The gateway closes after $DRAIN: 1 admissions under way are refused when they get " +
            "past admission, 0 detached commands running are cancelled."

        /** Far past the drain timeout: a stop the gateway does not hold ends well within it. */
        val STOPPED_WITHIN = 10.seconds
    }
}
