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

package me.ahoo.wow.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.util.concurrent.TimeUnit

class MetricCommandBusTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val metrics = WowMetrics(meterRegistry)

    @Test
    fun `receiver preserves delegate readiness`() {
        val readiness = Sinks.empty<Void>()
        val command = TestCommandMessage(id = "command-id")
        val receiver = metricCommandBus(
            RecordingLocalCommandBus(readiness = readiness.asMono())
        ).receiver(
            MessageSubscription(command.aggregateId.namedAggregate),
        )
        val ready = receiver.readiness.toFuture()

        ready.isDone.assert().isFalse()
        readiness.tryEmitEmpty().orThrow()
        ready.get(1, TimeUnit.SECONDS)
    }

    @Test
    fun `runtime receiver preserves delegate runtime admission protocol`() {
        val command = TestCommandMessage(id = "command-id")
        val delegate = RecordingLocalCommandBus()
        val subscription = MessageSubscription(command.aggregateId.namedAggregate)

        metricCommandBus(delegate).runtimeReceiver(subscription)

        delegate.runtimeSubscriptions.assert().containsExactly(subscription)
    }

    @Test
    fun `send should record semantic metric and delegate command`() {
        val delegate = RecordingLocalCommandBus()
        val command = TestCommandMessage(id = "command-id")
        val commandBus = metricCommandBus(delegate)
        val publisher = commandBus.send(command)

        StepVerifier.create(publisher)
            .verifyComplete()

        delegate.sent.single().assert().isSameAs(command)
        meterRegistry.get(WowMetricNames.OPERATION)
            .tags("component", "command_bus", "operation", "send")
            .timer()
            .count()
            .assert()
            .isEqualTo(1)
    }

    @Test
    fun `receive should delegate exchanges`() {
        val command = TestCommandMessage(id = "command-id")
        val exchange = SimpleServerCommandExchange(command)
        val delegate = RecordingLocalCommandBus(
            receiveFlux = Flux.just(exchange)
        )
        val commandBus = metricCommandBus(delegate)
        val subscription = MessageSubscription(command.aggregateId.namedAggregate, receiverGroup = "test-group")
        val publisher = commandBus.receive(subscription)

        StepVerifier.create(publisher)
            .expectNext(exchange)
            .verifyComplete()

        delegate.received.single().assert().isEqualTo(subscription)
    }

    @Test
    fun `receive should apply metrics subscriber context`() {
        val command = TestCommandMessage(id = "command-id")
        val exchange = SimpleServerCommandExchange(command)
        val delegate = RecordingLocalCommandBus(
            receiveFlux = Flux.just(exchange)
        )
        val commandBus = metricCommandBus(delegate)

        StepVerifier.create(
            commandBus.receive(MessageSubscription(command.aggregateId.namedAggregate))
                .writeMetricsSubscriber("command-handler")
        )
            .expectNext(exchange)
            .verifyComplete()
    }

    @Test
    fun `receive should use receiver group as default subscriber tag`() {
        withMeterRegistry { meterRegistry ->
            val commandBus = metricCommandBus(RecordingLocalCommandBus())
            val subscription = MessageSubscription(
                MaterializedNamedAggregate("sales", "Order"),
                receiverGroup = "order-handler",
            )

            commandBus.receive(subscription).blockLast()

            meterRegistry.receiveMeterIds()
                .mapNotNull { it.getTag(MetricDescriptor.SUBSCRIBER_TAG) }
                .toSet()
                .assert().containsExactly("order-handler")
        }
    }

    @Test
    fun `receive should bound multiple aggregate cardinality`() {
        withMeterRegistry { meterRegistry ->
            val commandBus = metricCommandBus(RecordingLocalCommandBus())
            val inventory = MaterializedNamedAggregate("sales", "Inventory")
            val payment = MaterializedNamedAggregate("sales", "Payment")

            commandBus.receive(MessageSubscription(linkedSetOf(payment, inventory), "handler"))
                .blockLast()
            commandBus.receive(MessageSubscription(linkedSetOf(inventory, payment), "handler"))
                .blockLast()

            meterRegistry.receiveMeterIds()
                .mapNotNull { it.getTag(MetricDescriptor.AGGREGATE_TAG) }
                .toSet()
                .assert().containsExactly(MetricDescriptor.MULTIPLE)
        }
    }

    @Test
    fun `local command bus should delegate subscriber count and close`() {
        val command = TestCommandMessage(id = "command-id")
        val delegate = RecordingLocalCommandBus(subscribers = 3)
        val commandBus = MetricLocalCommandBus(delegate, metrics, "commandBus")

        commandBus.subscriberCount(command.aggregateId.namedAggregate).assert().isEqualTo(3)

        commandBus.close()

        delegate.closed.assert().isTrue()
    }

    @Test
    fun `local command bus preserves atomic delivery receipt`() {
        val command = TestCommandMessage(id = "command-id")
        val delegate = RecordingLocalCommandBus(localDelivery = false)
        val commandBus = MetricLocalCommandBus(delegate, metrics, "commandBus")

        StepVerifier.create(commandBus.sendIfSubscribed(command))
            .expectNext(false)
            .verifyComplete()

        delegate.localDeliveryAttempts.single().assert().isSameAs(command)
        delegate.sent.assert().isEmpty()
    }

    private fun withMeterRegistry(block: (SimpleMeterRegistry) -> Unit) {
        meterRegistry.clear()
        block(meterRegistry)
    }

    private fun SimpleMeterRegistry.receiveMeterIds() = meters
        .map { it.id }
        .filter { it.name == WowMetricNames.STREAM_MESSAGES }
        .filter { it.getTag(MetricDescriptor.COMPONENT_TAG) == "command_bus" }
        .filter { it.getTag(MetricDescriptor.OPERATION_TAG) == "receive" }

    private fun metricCommandBus(delegate: RecordingLocalCommandBus): MetricCommandBus<LocalCommandBus> =
        MetricCommandBus(delegate, metrics, "commandBus")
}

private class RecordingLocalCommandBus(
    private val subscribers: Int = 0,
    private val receiveFlux: Flux<ServerCommandExchange<*>> = Flux.empty(),
    private val readiness: Mono<Void> = Mono.empty(),
    private val localDelivery: Boolean = false,
) : LocalCommandBus {
    val sent: MutableList<CommandMessage<*>> = mutableListOf()
    val localDeliveryAttempts: MutableList<CommandMessage<*>> = mutableListOf()
    val received: MutableList<MessageSubscription> = mutableListOf()
    val runtimeSubscriptions: MutableList<MessageSubscription> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun send(message: CommandMessage<*>): Mono<Void> =
        Mono.fromRunnable {
            sent += message
        }

    override fun sendIfSubscribed(message: CommandMessage<*>): Mono<Boolean> =
        Mono.fromSupplier {
            localDeliveryAttempts += message
            localDelivery
        }

    override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> {
        received += subscription
        return receiveFlux
    }

    override fun receiver(
        subscription: MessageSubscription,
    ): MessageReceiver<ServerCommandExchange<*>> =
        MessageReceiver(
            messages = receive(subscription),
            readiness = readiness,
        )

    override fun runtimeReceiver(
        subscription: MessageSubscription,
    ): MessageReceiver<ServerCommandExchange<*>> =
        receiver(subscription).also {
            runtimeSubscriptions += subscription
        }

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = subscribers

    override fun close() {
        closed = true
    }
}
