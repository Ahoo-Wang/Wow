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
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import org.junit.jupiter.api.Test
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import io.micrometer.core.instrument.Metrics as MicrometerMetrics

class MetricCommandBusTest {

    @Test
    fun `send should name publisher and delegate command`() {
        val delegate = RecordingLocalCommandBus()
        val command = TestCommandMessage(id = "command-id")
        val commandBus = MetricCommandBus(delegate)
        val publisher = commandBus.send(command)

        Scannable.from(publisher).name().assert().isEqualTo("wow.command.send")

        StepVerifier.create(publisher)
            .verifyComplete()

        delegate.sent.single().assert().isSameAs(command)
    }

    @Test
    fun `receive should delegate exchanges`() {
        val command = TestCommandMessage(id = "command-id")
        val exchange = SimpleServerCommandExchange(command)
        val delegate = RecordingLocalCommandBus(
            receiveFlux = Flux.just(exchange)
        )
        val commandBus = MetricCommandBus(delegate)
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
        val commandBus = MetricCommandBus(delegate)

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
            val commandBus = MetricCommandBus(RecordingLocalCommandBus())
            val subscription = MessageSubscription(
                MaterializedNamedAggregate("sales", "Order"),
                receiverGroup = "order-handler",
            )

            commandBus.receive(subscription).blockLast()

            meterRegistry.receiveMeterIds()
                .mapNotNull { it.getTag(Metrics.SUBSCRIBER_KEY) }
                .toSet()
                .assert().containsExactly("order-handler")
        }
    }

    @Test
    fun `receive should canonicalize aggregate tag order`() {
        withMeterRegistry { meterRegistry ->
            val commandBus = MetricCommandBus(RecordingLocalCommandBus())
            val inventory = MaterializedNamedAggregate("sales", "Inventory")
            val payment = MaterializedNamedAggregate("sales", "Payment")

            commandBus.receive(MessageSubscription(linkedSetOf(payment, inventory), "handler"))
                .blockLast()
            commandBus.receive(MessageSubscription(linkedSetOf(inventory, payment), "handler"))
                .blockLast()

            meterRegistry.receiveMeterIds()
                .mapNotNull { it.getTag(Metrics.AGGREGATE_KEY) }
                .filter { "Inventory" in it || "Payment" in it }
                .toSet()
                .assert().containsExactly("Inventory,Payment")
        }
    }

    @Test
    fun `local command bus should delegate subscriber count and close`() {
        val command = TestCommandMessage(id = "command-id")
        val delegate = RecordingLocalCommandBus(subscribers = 3)
        val commandBus = MetricLocalCommandBus(delegate)

        commandBus.subscriberCount(command.aggregateId.namedAggregate).assert().isEqualTo(3)

        commandBus.close()

        delegate.closed.assert().isTrue()
    }

    private fun withMeterRegistry(block: (SimpleMeterRegistry) -> Unit) {
        val meterRegistry = SimpleMeterRegistry()
        MicrometerMetrics.addRegistry(meterRegistry)
        try {
            block(meterRegistry)
        } finally {
            MicrometerMetrics.removeRegistry(meterRegistry)
            meterRegistry.close()
        }
    }

    private fun SimpleMeterRegistry.receiveMeterIds() = meters
        .map { it.id }
        .filter { it.name.startsWith("wow.command.receive") }
}

private class RecordingLocalCommandBus(
    private val subscribers: Int = 0,
    private val receiveFlux: Flux<ServerCommandExchange<*>> = Flux.empty(),
) : LocalCommandBus {
    val sent: MutableList<CommandMessage<*>> = mutableListOf()
    val received: MutableList<MessageSubscription> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun send(message: CommandMessage<*>): Mono<Void> =
        Mono.fromRunnable {
            sent += message
        }

    override fun receive(subscription: MessageSubscription): Flux<ServerCommandExchange<*>> {
        received += subscription
        return receiveFlux
    }

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = subscribers

    override fun close() {
        closed = true
    }
}
