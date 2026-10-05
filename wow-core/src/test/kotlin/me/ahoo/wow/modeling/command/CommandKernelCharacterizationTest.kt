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

package me.ahoo.wow.modeling.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.messaging.function.materialize
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.command.SimpleServerCommandExchange
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.InMemoryEventStore
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.ioc.SimpleServiceProvider
import me.ahoo.wow.ioc.register
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.metadata.AggregateMetadata
import me.ahoo.wow.modeling.state.ConstructorStateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import reactor.core.Exceptions
import java.time.Duration

/**
 * Locks the observable behaviour of the command kernel — what a command handler's result turns into, how its
 * failures propagate, what it can inject and which function the exchange reports — through the public command
 * aggregate SPI, so that rebuilding the kernel cannot change it unnoticed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommandKernelCharacterizationTest {

    private class Outcome(
        val exchange: ServerCommandExchange<*>,
        val aggregate: CommandAggregate<*, *>,
        val eventStore: InMemoryEventStore,
        val emitted: DomainEventStream?,
        val error: Throwable?
    ) {
        val state: StateAggregate<*> get() = aggregate.state
        val emittedBodies: List<Any>? get() = emitted?.map { it.body }
        val storedVersions: List<Int>
            get() = eventStore.load(aggregate.aggregateId).map { it.version }.collectList().block()!!
    }

    private fun <C : Any> run(
        aggregateType: Class<C>,
        command: Any,
        serviceProvider: ServiceProvider = SimpleServiceProvider()
    ): Outcome {
        val metadata: AggregateMetadata<C, Any> = aggregateType.aggregateMetadata()
        val message = command.toCommandMessage(aggregateId = "kernel-1")
        val stateAggregate = ConstructorStateAggregateFactory.create(metadata.state, message.aggregateId)
        val eventStore = InMemoryEventStore()
        val aggregate = SimpleCommandAggregateFactory(eventStore).create(metadata, stateAggregate)
        return process(aggregate, eventStore, message, serviceProvider)
    }

    private fun process(
        aggregate: CommandAggregate<*, *>,
        eventStore: InMemoryEventStore,
        message: CommandMessage<*>,
        serviceProvider: ServiceProvider = SimpleServiceProvider()
    ): Outcome {
        val exchange = SimpleServerCommandExchange(message).setServiceProvider(serviceProvider)
        var emitted: DomainEventStream? = null
        var error: Throwable? = null
        try {
            emitted = aggregate.process(exchange).block(Duration.ofSeconds(10))
        } catch (failure: Throwable) {
            error = Exceptions.unwrap(failure)
        }
        return Outcome(exchange, aggregate, eventStore, emitted, error)
    }

    private fun characterize(command: Any): Outcome = run(KernelCharacterizationAggregate::class.java, command)

    private val Outcome.sourced: List<Any>
        get() = (aggregate.commandRoot as KernelCharacterizationAggregate).sourced

    private fun Outcome.assertCommittedOne(vararg bodies: Any) {
        error.assert().isNull()
        emittedBodies.assert().isEqualTo(bodies.toList())
        exchange.getEventStream().assert().isSameAs(emitted)
        state.version.assert().isEqualTo(1)
        storedVersions.assert().isEqualTo(listOf(1))
    }

    /** The handler produced no result: the processing completes empty and nothing is decided, applied or stored. */
    private fun Outcome.assertCompletedEmpty() {
        error.assert().isNull()
        emitted.assert().isNull()
        exchange.getEventStream().assert().isNull()
        state.version.assert().isEqualTo(0)
        storedVersions.assert().isEmpty()
    }

    private fun Outcome.assertFailed(type: Class<out Throwable>, message: String) {
        error.assert().isInstanceOf(type)
        error!!.message.assert().isEqualTo(message)
        exchange.getEventStream().assert().isNull()
        state.version.assert().isEqualTo(0)
        storedVersions.assert().isEmpty()
    }

    @BeforeEach
    fun resetProbe() {
        KernelProbe.reset()
    }

    @Nested
    inner class ResultShapes {
        @Test
        fun `a single event is committed and sourced`() {
            val outcome = characterize(ReturnSingle("id"))
            outcome.assertCommittedOne(KernelEvent("single"))
            outcome.sourced.assert().isEqualTo(listOf(KernelEvent("single")))
            outcome.exchange.getCommandInvokeResult<Any>().assert().isEqualTo(KernelEvent("single"))
        }

        @Test
        fun `a list and an array are flattened into one stream`() {
            characterize(ReturnList("id")).assertCommittedOne(KernelEvent("first"), OtherKernelEvent("second"))
            characterize(ReturnArray("id")).assertCommittedOne(KernelEvent("first"), OtherKernelEvent("second"))
        }

        @Test
        fun `reactive and coroutine results are collected`() {
            characterize(ReturnMono("id")).assertCommittedOne(KernelEvent("mono"))
            characterize(ReturnFlux("id")).assertCommittedOne(KernelEvent("first"), OtherKernelEvent("second"))
            characterize(ReturnPublisher("id")).assertCommittedOne(KernelEvent("publisher"))
            characterize(ReturnFlow("id")).assertCommittedOne(KernelEvent("first"), OtherKernelEvent("second"))
            characterize(SuspendSingle("id")).assertCommittedOne(KernelEvent("suspend"))
        }

        @Test
        fun `a flux or flow result keeps the collected list as the command invoke result`() {
            characterize(ReturnFlux("id")).exchange.getCommandInvokeResult<Any>()
                .assert().isEqualTo(listOf(KernelEvent("first"), OtherKernelEvent("second")))
        }

        @Test
        fun `null, Unit and an empty Mono complete without deciding anything`() {
            characterize(ReturnNull("id")).assertCompletedEmpty()
            characterize(ReturnUnit("id")).assertCompletedEmpty()
            characterize(ReturnMonoEmpty("id")).assertCompletedEmpty()
        }

        @Test
        fun `a suspend handler returning null fails the command`() {
            val outcome = characterize(SuspendNull("id"))
            outcome.error.assert().isInstanceOf(NullPointerException::class.java)
            outcome.storedVersions.assert().isEmpty()
        }

        @Test
        fun `a suspend handler returning Unit commits Unit as its event`() {
            val outcome = characterize(SuspendUnit("id"))
            outcome.error.assert().isNull()
            outcome.emittedBodies.assert().isEqualTo(listOf(Unit))
            outcome.storedVersions.assert().isEqualTo(listOf(1))
        }

        @Test
        fun `an empty list, flux or flow fails the command`() {
            characterize(ReturnEmptyList("id")).assertFailed(IllegalArgumentException::class.java, EMPTY_EVENTS)
            characterize(ReturnFluxEmpty("id")).assertFailed(IllegalArgumentException::class.java, EMPTY_EVENTS)
            characterize(ReturnFlowEmpty("id")).assertFailed(IllegalArgumentException::class.java, EMPTY_EVENTS)
        }

        @Test
        fun `a null element fails the command`() {
            characterize(ReturnNullElement("id"))
                .assertFailed(IllegalArgumentException::class.java, "Domain event at index[1] must not be null.")
        }
    }

    @Nested
    inner class AfterCommandFunctions {
        private fun after(command: Any): Outcome = run(KernelAfterCommandAggregate::class.java, command)

        @Test
        fun `with an after-command function an empty result commits the after-command events`() {
            after(ReturnNull("id")).assertCommittedOne(OtherKernelEvent("after"))
            after(ReturnEmptyList("id")).assertCommittedOne(OtherKernelEvent("after"))
        }

        @Test
        fun `after-command events follow the handler's events and see its result`() {
            after(ReturnSingle("id")).assertCommittedOne(
                KernelEvent("single"),
                OtherKernelEvent("after:KernelEvent(value=single)"),
            )
        }

        @Test
        fun `a failing handler skips the after-command functions`() {
            after(ThrowSync("id")).assertFailed(IllegalStateException::class.java, "sync")
        }
    }

    @Nested
    inner class Failures {
        @Test
        fun `exceptions thrown by sync, reactive and suspend handlers propagate unwrapped`() {
            characterize(ThrowSync("id")).assertFailed(IllegalStateException::class.java, "sync")
            characterize(ThrowMono("id")).assertFailed(IllegalStateException::class.java, "mono")
            characterize(MonoError("id")).assertFailed(IllegalStateException::class.java, "mono-error")
            characterize(ThrowFlux("id")).assertFailed(IllegalStateException::class.java, "flux")
            characterize(ThrowPublisher("id")).assertFailed(IllegalStateException::class.java, "publisher")
            characterize(ThrowSuspend("id")).assertFailed(IllegalStateException::class.java, "suspend")
            characterize(ThrowInsideFlow("id")).assertFailed(IllegalStateException::class.java, "inside-flow")
        }

        /** Changed on purpose by K2 (B10): before it, the error arrived wrapped in an InvocationTargetException. */
        @Test
        fun `an exception thrown while creating a flow propagates unwrapped`() {
            characterize(ThrowFlow("id")).assertFailed(IllegalStateException::class.java, "flow")
        }

        @Test
        fun `on error receives the handler error and the exchange reports the command function`() {
            val outcome = characterize(FailingWithErrorHandler("id"))
            outcome.assertFailed(IllegalStateException::class.java, "handled")
            KernelProbe.received["error"].assert().isSameAs(outcome.error)
            (KernelProbe.received["function"] as FunctionInfo).materialize().assert().isEqualTo(
                commandFunction(processorName = "KernelCharacterizationAggregate", name = "onCommand"),
            )
        }
    }

    @Nested
    inner class Dispatch {
        @Test
        fun `a command message or exchange first parameter receives the message or exchange`() {
            characterize(ByMessage("by-message")).assertCommittedOne(KernelEvent("by-message"))
            characterize(ByExchange("by-exchange")).assertCommittedOne(KernelEvent("by-exchange"))
        }

        @Test
        fun `of two handlers for one command type the first one found wins`() {
            characterize(Duplicated("id")).assertCommittedOne(OtherKernelEvent("duplicated"))
        }

        /** Changed on purpose by K1 (B11): before it, both commands were undefined. */
        @Test
        fun `a command matches a handler that takes its superclass or interface`() {
            characterize(SubCommand("id")).assertCommittedOne(KernelEvent("base"))
            characterize(MarkedCommand("id")).assertCommittedOne(KernelEvent("marker"))
        }

        @Test
        fun `the superclass handler wins over the interface handler and Any never matches`() {
            characterize(BaseAndMarkedCommand("id")).assertCommittedOne(KernelEvent("base"))
            characterize(UnhandledCommand("id")).error.assert().isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `after-command functions apply by the command's own type when its handler matched a supertype`() {
            run(KernelAfterCommandAggregate::class.java, SubCommand("id"))
                .assertCommittedOne(KernelEvent("base"), OtherKernelEvent("after"))
        }
    }

    @Nested
    inner class Injection {
        @Test
        fun `handler parameters resolve from the exchange, then the service provider`() {
            val service = KernelService()
            val serviceProvider = SimpleServiceProvider()
            serviceProvider.register(service)
            serviceProvider.register("named-value", "namedService")

            val outcome = run(KernelCharacterizationAggregate::class.java, InjectEverything("id"), serviceProvider)

            outcome.assertCommittedOne(KernelEvent("injected"))
            val received = KernelProbe.received
            received["exchange"].assert().isSameAs(outcome.exchange)
            received["message"].assert().isSameAs(outcome.exchange.message)
            received["header"].assert().isSameAs(outcome.exchange.message.header)
            received["aggregateId"].assert().isSameAs(outcome.exchange.message.aggregateId)
            received["commandAggregate"].assert().isSameAs(outcome.aggregate)
            received["stateAggregate"].assert().isSameAs(outcome.state)
            received["serviceProvider"].assert().isSameAs(serviceProvider)
            received["service"].assert().isSameAs(service)
            received["nullableService"].assert().isSameAs(service)
            received["namedService"].assert().isEqualTo("named-value")
            received["missing"].assert().isNull()
        }

        @Test
        fun `an unresolvable parameter is injected as null`() {
            val outcome = run(KernelCharacterizationAggregate::class.java, InjectEverything("id"))
            outcome.error.assert().isNull()
            KernelProbe.received["service"].assert().isNull()
        }
    }

    @Nested
    inner class Sourcing {
        @Test
        fun `a sourcing function can take the domain event exchange`() {
            val outcome = characterize(SourcedThroughExchange("id"))
            outcome.assertCommittedOne(ExchangeSourcedEvent("exchange"))
            outcome.sourced.assert().isEqualTo(listOf(ExchangeSourcedEvent("exchange")))
        }

        @Test
        fun `a sourcing function can inject the domain event and its aggregate id`() {
            val outcome = characterize(SourcedWithInjection("id"))
            outcome.assertCommittedOne(InjectedSourcedEvent("injected"))
            outcome.sourced.assert().isEqualTo(
                listOf(
                    listOf(
                        InjectedSourcedEvent("injected"),
                        InjectedSourcedEvent("injected"),
                        outcome.aggregate.aggregateId.id,
                        null,
                    ),
                ),
            )
        }
    }

    @Nested
    inner class ReportedFunction {
        @Test
        fun `a successful command reports its handler`() {
            characterize(ReturnSingle("id")).exchange.getFunction()!!.materialize().assert().isEqualTo(
                commandFunction(processorName = "KernelCharacterizationAggregate", name = "onCommand"),
            )
        }

        @Test
        fun `a rejected command reports the command aggregate`() {
            val metadata = aggregateMetadata<KernelCharacterizationAggregate, KernelCharacterizationAggregate>()
            val message = UpdateKernel("id").toCommandMessage(aggregateId = "kernel-1", aggregateVersion = 3)
            val state = ConstructorStateAggregateFactory.create(metadata.state, message.aggregateId)
            val eventStore = InMemoryEventStore()
            val outcome =
                process(SimpleCommandAggregateFactory(eventStore).create(metadata, state), eventStore, message)

            outcome.error.assert().isInstanceOf(CommandExpectVersionConflictException::class.java)
            outcome.exchange.getFunction()!!.materialize().assert().isEqualTo(
                FunctionInfoData(
                    functionKind = FunctionKind.COMMAND,
                    contextName = "wow",
                    processorName = "SimpleCommandAggregate",
                    name = "process",
                ),
            )
        }

        @Test
        fun `a built-in command reports the aggregate and the command type`() {
            val metadata = aggregateMetadata<KernelCharacterizationAggregate, KernelCharacterizationAggregate>()
            val create = ReturnSingle("id").toCommandMessage(aggregateId = "kernel-1")
            val state = ConstructorStateAggregateFactory.create(metadata.state, create.aggregateId)
            val eventStore = InMemoryEventStore()
            val aggregate = SimpleCommandAggregateFactory(eventStore).create(metadata, state)
            process(aggregate, eventStore, create).error.assert().isNull()

            val delete = DefaultDeleteAggregate.toCommandMessage(
                aggregateId = create.aggregateId.id,
                namedAggregate = create.aggregateId.namedAggregate,
            )
            val outcome = process(aggregate, eventStore, delete)

            outcome.error.assert().isNull()
            outcome.state.deleted.assert().isTrue()
            outcome.exchange.getFunction()!!.materialize().assert().isEqualTo(
                commandFunction(
                    processorName = "KernelCharacterizationAggregate",
                    name = "KernelCharacterizationAggregate.DefaultDeleteAggregate",
                ),
            )
        }

        @Test
        fun `an inherited handler reports the runtime aggregate type as its processor`() {
            val outcome = run(KernelInheritingAggregate::class.java, ReturnSingle("id"))
            outcome.assertCommittedOne(KernelEvent("inherited"))
            outcome.exchange.getFunction()!!.materialize().assert().isEqualTo(
                commandFunction(processorName = "KernelInheritingAggregate", name = "onCommand"),
            )
        }
    }

    private companion object {
        const val EMPTY_EVENTS = "events can not be empty."
    }

    private fun commandFunction(processorName: String, name: String) = FunctionInfoData(
        functionKind = FunctionKind.COMMAND,
        contextName = "wow-core-test",
        processorName = processorName,
        name = name,
    )
}
