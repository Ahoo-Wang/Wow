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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.annotation.AfterCommand
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.CreateAggregate
import me.ahoo.wow.api.annotation.Name
import me.ahoo.wow.api.annotation.OnCommand
import me.ahoo.wow.api.annotation.OnError
import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.aware.VersionAware
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.ioc.ServiceProvider
import me.ahoo.wow.modeling.state.StateAggregate
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/*
 * Fixtures for CommandKernelCharacterizationTest: one command per handler shape the command kernel must keep
 * behaving the same way. Every command creates the aggregate, so each case runs on a fresh state.
 */

@CreateAggregate
data class ReturnSingle(val id: String)

@CreateAggregate
data class ReturnNull(val id: String)

@CreateAggregate
data class ReturnUnit(val id: String)

@CreateAggregate
data class ReturnEmptyList(val id: String)

@CreateAggregate
data class ReturnList(val id: String)

@CreateAggregate
data class ReturnArray(val id: String)

@CreateAggregate
data class ReturnMono(val id: String)

@CreateAggregate
data class ReturnMonoEmpty(val id: String)

@CreateAggregate
data class ReturnFlux(val id: String)

@CreateAggregate
data class ReturnFluxEmpty(val id: String)

@CreateAggregate
data class ReturnPublisher(val id: String)

@CreateAggregate
data class ReturnFlow(val id: String)

@CreateAggregate
data class ReturnFlowEmpty(val id: String)

@CreateAggregate
data class SuspendSingle(val id: String)

@CreateAggregate
data class SuspendNull(val id: String)

@CreateAggregate
data class SuspendUnit(val id: String)

@CreateAggregate
data class ThrowSync(val id: String)

@CreateAggregate
data class ThrowMono(val id: String)

@CreateAggregate
data class MonoError(val id: String)

@CreateAggregate
data class ThrowFlux(val id: String)

@CreateAggregate
data class ThrowPublisher(val id: String)

@CreateAggregate
data class ThrowSuspend(val id: String)

@CreateAggregate
data class ThrowFlow(val id: String)

@CreateAggregate
data class ThrowInsideFlow(val id: String)

@CreateAggregate
data class ReturnNullElement(val id: String)

@CreateAggregate
data class InjectEverything(val id: String)

@CreateAggregate
data class ByMessage(val id: String)

@CreateAggregate
data class ByExchange(val id: String)

@CreateAggregate
data class SourcedThroughExchange(val id: String)

@CreateAggregate
data class SourcedWithInjection(val id: String)

@CreateAggregate
data class Duplicated(val id: String)

@AllowCreate
data class UpdateKernel(val id: String)

@CreateAggregate
data class FailingWithErrorHandler(val id: String)

@CreateAggregate
open class BaseCommand(open val id: String)

@CreateAggregate
data class SubCommand(override val id: String) : BaseCommand(id)

interface MarkerCommand

@CreateAggregate
data class BaseAndMarkedCommand(override val id: String) : BaseCommand(id), MarkerCommand

@CreateAggregate
data class UnhandledCommand(val id: String)

interface FirstMarker

interface SecondMarker

@CreateAggregate
data class DoublyMarkedCommand(val id: String) : FirstMarker, SecondMarker

@CreateAggregate
data class MarkedCommand(val id: String) : MarkerCommand

data class KernelEvent(val value: String)

data class OtherKernelEvent(val value: String)

data class ExchangeSourcedEvent(val value: String)

data class InjectedSourcedEvent(val value: String)

class KernelService

class MissingKernelService

/** What the injectable handlers received, read by the test after a command. */
object KernelProbe {
    @Volatile
    var received: Map<String, Any?> = emptyMap()

    fun reset() {
        received = emptyMap()
    }
}

// Wow finds and invokes the private handlers by reflection.
@Suppress(
    "UnusedPrivateMember",
    "UNUSED_PARAMETER",
    "TooManyFunctions",
    "LongParameterList",
    "FunctionOnlyReturningConstant"
)
class KernelCharacterizationAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION
    val sourced: MutableList<Any> = mutableListOf()

    fun id(): String = id

    private fun onCommand(command: ReturnSingle): KernelEvent = KernelEvent("single")

    private fun onCommand(command: ReturnNull): KernelEvent? = null

    @OnCommand
    private fun returnUnit(command: ReturnUnit) = Unit

    private fun onCommand(command: ReturnEmptyList): List<Any> = emptyList()

    private fun onCommand(command: ReturnList): List<Any> = listOf(KernelEvent("first"), OtherKernelEvent("second"))

    private fun onCommand(command: ReturnArray): Array<Any> = arrayOf(KernelEvent("first"), OtherKernelEvent("second"))

    private fun onCommand(command: ReturnMono): Mono<KernelEvent> = Mono.just(KernelEvent("mono"))

    private fun onCommand(command: ReturnMonoEmpty): Mono<KernelEvent> = Mono.empty()

    private fun onCommand(command: ReturnFlux): Flux<Any> = Flux.just(KernelEvent("first"), OtherKernelEvent("second"))

    private fun onCommand(command: ReturnFluxEmpty): Flux<Any> = Flux.empty()

    private fun onCommand(command: ReturnPublisher): Publisher<Any> = Flux.just(KernelEvent("publisher"))

    private fun onCommand(command: ReturnFlow): Flow<Any> = flowOf(KernelEvent("first"), OtherKernelEvent("second"))

    private fun onCommand(command: ReturnFlowEmpty): Flow<Any> = emptyFlow()

    private suspend fun onCommand(command: SuspendSingle): KernelEvent = KernelEvent("suspend")

    private suspend fun onCommand(command: SuspendNull): KernelEvent? = null

    @OnCommand
    private suspend fun suspendUnit(command: SuspendUnit) = Unit

    private fun onCommand(command: ThrowSync): KernelEvent = throw IllegalStateException("sync")

    private fun onCommand(command: ThrowMono): Mono<KernelEvent> = throw IllegalStateException("mono")

    private fun onCommand(command: MonoError): Mono<KernelEvent> = Mono.error(IllegalStateException("mono-error"))

    private fun onCommand(command: ThrowFlux): Flux<KernelEvent> = throw IllegalStateException("flux")

    private fun onCommand(command: ThrowPublisher): Publisher<KernelEvent> = throw IllegalStateException("publisher")

    private suspend fun onCommand(command: ThrowSuspend): KernelEvent = throw IllegalStateException("suspend")

    private fun onCommand(command: ThrowFlow): Flow<KernelEvent> = throw IllegalStateException("flow")

    private fun onCommand(command: ThrowInsideFlow): Flow<KernelEvent> = flow {
        throw IllegalStateException("inside-flow")
    }

    private fun onCommand(command: ReturnNullElement): List<KernelEvent?> = listOf(KernelEvent("first"), null)

    private fun onCommand(
        command: InjectEverything,
        exchange: ServerCommandExchange<*>,
        message: CommandMessage<*>,
        header: Header,
        aggregateId: AggregateId,
        commandAggregate: CommandAggregate<*, *>,
        stateAggregate: StateAggregate<*>,
        serviceProvider: ServiceProvider,
        service: KernelService,
        nullableService: KernelService?,
        @Name("namedService") namedService: Any?,
        missing: MissingKernelService?
    ): KernelEvent {
        KernelProbe.received = mapOf(
            "exchange" to exchange,
            "message" to message,
            "header" to header,
            "aggregateId" to aggregateId,
            "commandAggregate" to commandAggregate,
            "stateAggregate" to stateAggregate,
            "serviceProvider" to serviceProvider,
            "service" to service,
            "nullableService" to nullableService,
            "namedService" to namedService,
            "missing" to missing,
        )
        return KernelEvent("injected")
    }

    private fun onCommand(command: CommandMessage<ByMessage>): KernelEvent = KernelEvent(command.body.id)

    private fun onCommand(exchange: ServerCommandExchange<ByExchange>): KernelEvent =
        KernelEvent(exchange.message.body.id)

    private fun onCommand(command: SourcedThroughExchange): ExchangeSourcedEvent = ExchangeSourcedEvent("exchange")

    private fun onCommand(command: SourcedWithInjection): InjectedSourcedEvent = InjectedSourcedEvent("injected")

    private fun onCommand(command: UpdateKernel): KernelEvent = KernelEvent("update")

    private fun onCommand(command: Duplicated): KernelEvent = KernelEvent("onCommand")

    @OnCommand
    private fun duplicated(command: Duplicated): OtherKernelEvent = OtherKernelEvent("duplicated")

    private fun onCommand(command: FailingWithErrorHandler): KernelEvent = throw IllegalStateException("handled")

    @OnError
    private fun onError(command: FailingWithErrorHandler, error: Throwable, exchange: ServerCommandExchange<*>) {
        KernelProbe.received = mapOf("error" to error, "function" to exchange.getFunction())
    }

    private fun onCommand(command: BaseCommand): KernelEvent = KernelEvent("base")

    private fun onCommand(command: MarkerCommand): KernelEvent = KernelEvent("marker")

    private fun onCommand(command: FirstMarker): KernelEvent = KernelEvent("first-marker")

    private fun onCommand(command: SecondMarker): KernelEvent = KernelEvent("second-marker")

    /** Never matches polymorphically: only a command whose type is exactly `Any` would reach it. */
    private fun onCommand(command: Any): KernelEvent = KernelEvent("any")

    private fun onSourcing(event: KernelEvent) {
        sourced += event
    }

    private fun onSourcing(event: OtherKernelEvent) {
        sourced += event
    }

    @OnSourcing
    private fun sourceThroughExchange(exchange: DomainEventExchange<ExchangeSourcedEvent>) {
        sourced += exchange.message.body
    }

    @OnSourcing
    private fun sourceWithInjection(
        event: InjectedSourcedEvent,
        domainEvent: DomainEvent<*>,
        aggregateId: AggregateId,
        missing: MissingKernelService?
    ) {
        sourced += listOf(event, domainEvent.body, aggregateId.id, missing)
    }
}

/** An aggregate whose after-command function runs for every command, to show how it changes empty results. */
// Wow finds and invokes the private handlers by reflection.
@Suppress("UnusedPrivateMember", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
class KernelAfterCommandAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION

    fun id(): String = id

    private fun onCommand(command: ReturnNull): KernelEvent? = null

    private fun onCommand(command: ReturnEmptyList): List<Any> = emptyList()

    private fun onCommand(command: ReturnSingle): KernelEvent = KernelEvent("single")

    private fun onCommand(command: ThrowSync): KernelEvent = throw IllegalStateException("sync")

    private fun onCommand(command: BaseCommand): KernelEvent = KernelEvent("base")

    @AfterCommand(exclude = [ReturnSingle::class, ThrowSync::class])
    private fun afterCommand(command: Any): OtherKernelEvent = OtherKernelEvent("after")

    @AfterCommand(include = [ReturnSingle::class, ThrowSync::class])
    private fun afterSingle(exchange: ServerCommandExchange<Any>): OtherKernelEvent =
        OtherKernelEvent("after:${exchange.getCommandInvokeResult<Any>()}")

    private fun onSourcing(event: KernelEvent) = Unit

    private fun onSourcing(event: OtherKernelEvent) = Unit
}

/** Declares its handlers in a base class: the function names the runtime class as its processor. */
@Suppress("UnusedPrivateMember", "UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
abstract class KernelBaseAggregate(private val id: String) : VersionAware {
    override var version: Int = Version.UNINITIALIZED_VERSION

    fun id(): String = id

    fun onCommand(command: ReturnSingle): KernelEvent = KernelEvent("inherited")

    fun onSourcing(event: KernelEvent) = Unit
}

class KernelInheritingAggregate(id: String) : KernelBaseAggregate(id)
