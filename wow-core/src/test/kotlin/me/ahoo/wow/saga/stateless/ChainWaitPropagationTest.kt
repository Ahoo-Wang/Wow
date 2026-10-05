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

package me.ahoo.wow.saga.stateless

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.DefaultCommandGateway
import me.ahoo.wow.command.RequestIdChecker
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.validation.NoOpValidator
import me.ahoo.wow.command.wait.COMMAND_WAIT_PREFIX
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.command.wait.CommandWait
import me.ahoo.wow.command.wait.CommandWaitNotifier
import me.ahoo.wow.command.wait.DefaultWaitCoordinator
import me.ahoo.wow.command.wait.SimpleCommandWaitEndpoint
import me.ahoo.wow.command.wait.WAIT_COMMAND_ID
import me.ahoo.wow.command.wait.WaitPlan
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.chain.WaitingChainTail.Companion.COMMAND_WAIT_TAIL_STAGE
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.SimpleDomainEventExchange
import me.ahoo.wow.messaging.MessageReceiver
import me.ahoo.wow.messaging.MessageSubscription
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.tck.mock.MockAggregateCreated
import me.ahoo.wow.tck.mock.MockChangeAggregate
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

/**
 * B6: a chain wait reaches only the commands of the saga function it waits for. A command another saga function
 * derives from the same event carries no wait keys, while every command still carries the other propagated headers.
 */
class ChainWaitPropagationTest {
    private val endpoint = SimpleCommandWaitEndpoint("http://waiting-node/wait")
    private val sent = mutableListOf<CommandMessage<*>>()
    private val gateway = DefaultCommandGateway(
        commandWaitEndpoint = endpoint,
        commandBus = RecordingCommandBus(sent),
        validator = NoOpValidator,
        requestIdChecker = RequestIdChecker { _, _ -> Mono.just(true) },
        waitCoordinator = DefaultWaitCoordinator(),
        commandWaitNotifier = IgnoringNotifier,
    )

    private fun saga(name: String, result: Any): StatelessSagaFunction =
        StatelessSagaFunction(NamedSaga(name, Mono.just(result)), gateway, commandMessageFactory())

    private fun chainWaitingFor(saga: StatelessSagaFunction): WaitPlan =
        CommandWait.chain(
            waitCommandId = "upstream-command",
            function = NamedFunctionInfoData(saga.contextName, saga.processorName, saga.name),
            tailStage = CommandStage.PROCESSED,
            tailFunction = NamedFunctionInfoData("", "", ""),
        )

    private fun handle(saga: StatelessSagaFunction, plan: WaitPlan): CommandMessage<*> {
        val event = fixtureEvent()
        plan.propagate(endpoint, event.header)
        sent.clear()
        StepVerifier.create(saga.invoke(SimpleDomainEventExchange(event))).expectNextCount(1).verifyComplete()
        return sent.single()
    }

    @Test
    fun `the saga function the chain waits for sends its command with the chain tail`() {
        val target = saga("onTarget", MockChangeAggregate("target", "data"))

        val command = handle(target, chainWaitingFor(target))

        command.header[WAIT_COMMAND_ID].assert().isEqualTo("upstream-command")
        command.header[COMMAND_WAIT_TAIL_STAGE].assert().isEqualTo(CommandStage.PROCESSED.name)
    }

    @Test
    fun `another saga function reacting to the same event sends its command without wait keys`() {
        val target = saga("onTarget", MockChangeAggregate("target", "data"))
        val unrelated = saga("onUnrelated", MockChangeAggregate("unrelated", "data"))

        val command = handle(unrelated, chainWaitingFor(target))

        command.header.keys.filter { it.startsWith(COMMAND_WAIT_PREFIX) }.assert().isEmpty()
        command.header["trace_id"].assert().isEqualTo("trace-id")
    }

    @Test
    fun `a command message another saga function returns gets no wait keys either`() {
        val target = saga("onTarget", MockChangeAggregate("target", "data"))
        val unrelated = saga("onUnrelated", MockChangeAggregate("unrelated", "data").toCommandMessage())

        val command = handle(unrelated, chainWaitingFor(target))

        command.header.keys.filter { it.startsWith(COMMAND_WAIT_PREFIX) }.assert().isEmpty()
    }
}

private class NamedSaga(
    override val name: String,
    private val result: Mono<*>,
) : MessageFunction<Any, DomainEventExchange<*>, Mono<*>> {
    override val contextName: String = "fixture"
    override val processor: Any = "processor"
    override val supportedType: Class<*> = MockAggregateCreated::class.java
    override val supportedTopics: Set<NamedAggregate> = emptySet()
    override val functionKind: FunctionKind = FunctionKind.EVENT

    override fun <A : Annotation> getAnnotation(annotationClass: Class<A>): A? = null

    override fun invoke(exchange: DomainEventExchange<*>): Mono<*> = result
}

private class RecordingCommandBus(private val sent: MutableList<CommandMessage<*>>) : CommandBus {
    override fun send(message: CommandMessage<*>): Mono<Void> = Mono.fromRunnable { sent += message }

    override fun receiver(subscription: MessageSubscription): MessageReceiver<ServerCommandExchange<*>> =
        MessageReceiver(Flux.empty())
}

private object IgnoringNotifier : CommandWaitNotifier {
    override fun notify(commandWaitEndpoint: String, waitSignal: WaitSignal): Mono<Void> = Mono.empty()
}
