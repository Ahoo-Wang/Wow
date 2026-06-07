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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.wait.TestCommandMessage
import me.ahoo.wow.metrics.Metrics.writeMetricsSubscriber
import org.junit.jupiter.api.Test
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

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
        val namedAggregates = setOf(command.aggregateId.namedAggregate)
        val publisher = commandBus.receive(namedAggregates)

        StepVerifier.create(publisher)
            .expectNext(exchange)
            .verifyComplete()

        delegate.received.single().assert().isEqualTo(namedAggregates)
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
            commandBus.receive(setOf(command.aggregateId.namedAggregate))
                .writeMetricsSubscriber("command-handler")
        )
            .expectNext(exchange)
            .verifyComplete()
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
}

private class RecordingLocalCommandBus(
    private val subscribers: Int = 0,
    private val receiveFlux: Flux<ServerCommandExchange<*>> = Flux.empty(),
) : LocalCommandBus {
    val sent: MutableList<CommandMessage<*>> = mutableListOf()
    val received: MutableList<Set<NamedAggregate>> = mutableListOf()
    var closed: Boolean = false
        private set

    override fun send(message: CommandMessage<*>): Mono<Void> =
        Mono.fromRunnable {
            sent += message
        }

    override fun receive(namedAggregates: Set<NamedAggregate>): Flux<ServerCommandExchange<*>> {
        received += namedAggregates
        return receiveFlux
    }

    override fun subscriberCount(namedAggregate: NamedAggregate): Int = subscribers

    override fun close() {
        closed = true
    }
}
