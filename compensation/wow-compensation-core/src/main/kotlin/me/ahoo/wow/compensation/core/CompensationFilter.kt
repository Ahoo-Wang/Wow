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

package me.ahoo.wow.compensation.core

import me.ahoo.wow.api.annotation.ORDER_FIRST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.messaging.function.FunctionInfo
import me.ahoo.wow.api.messaging.function.materialize
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.EventHandledNotifierFilter
import me.ahoo.wow.command.wait.ProjectedNotifierFilter
import me.ahoo.wow.command.wait.SagaHandledNotifierFilter
import me.ahoo.wow.command.wait.SnapshotNotifierFilter
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId.Companion.toEventId
import me.ahoo.wow.compensation.api.RetrySpec.Companion.toSpec
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.event.EventExchange
import me.ahoo.wow.event.dispatcher.DomainEventDispatcher
import me.ahoo.wow.eventsourcing.snapshot.dispatcher.SnapshotDispatcher
import me.ahoo.wow.eventsourcing.state.StateEventExchange
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.filter.FilterChain
import me.ahoo.wow.filter.FilterType
import me.ahoo.wow.messaging.compensation.CompensationMatcher.compensationId
import me.ahoo.wow.messaging.function.MessageFunction
import me.ahoo.wow.messaging.handler.ExchangeFilter
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

fun FunctionInfo.getRetry(): Retry? {
    if (this !is MessageFunction<*, *, *>) {
        return null
    }
    return this.getAnnotation(Retry::class.java)
}

@FilterType(
    DomainEventDispatcher::class,
    StatelessSagaDispatcher::class,
    ProjectionDispatcher::class,
    SnapshotDispatcher::class
)
@Order(
    ORDER_FIRST,
    after = [EventHandledNotifierFilter::class, SagaHandledNotifierFilter::class, ProjectedNotifierFilter::class, SnapshotNotifierFilter::class],
    before = [RetryableFilter::class]
)
abstract class EventCompensationFilter<EXCHANGE : EventExchange<*, *>>(private val commandBus: CommandBus) :
    ExchangeFilter<EXCHANGE> {

    override fun filter(exchange: EXCHANGE, next: FilterChain<EXCHANGE>): Mono<Void> {
        val executionId = exchange.message.header.compensationId
        return next.filter(exchange)
            .onErrorResume {
                val eventFunction = exchange.getFunction() ?: return@onErrorResume it.toMono()
                val retry = eventFunction.getRetry()
                if (retry?.enabled == false) {
                    return@onErrorResume it.toMono()
                }
                val errorInfo = it.toErrorInfo()
                val errorDetails = ErrorDetails(
                    errorCode = errorInfo.errorCode,
                    errorMsg = errorInfo.errorMsg,
                    bindingErrors = errorInfo.bindingErrors,
                    stackTrace = it.stackTraceToString()
                )
                val recoverable = retry.recoverable(throwableClass = it.javaClass)
                val executeAt = System.currentTimeMillis()
                val command = if (executionId == null) {
                    CreateExecutionFailed(
                        eventId = exchange.message.toEventId(),
                        function = eventFunction.materialize(),
                        error = errorDetails,
                        executeAt = executeAt,
                        retrySpec = retry?.toSpec(),
                        recoverable = recoverable
                    )
                } else {
                    ApplyExecutionFailed(
                        id = executionId,
                        error = errorDetails,
                        executeAt = executeAt,
                        recoverable = recoverable
                    )
                }
                val commandMessage = command.toCommandMessage()
                commandBus.send(commandMessage).then(it.toMono())
            }
            .then(
                Mono.defer {
                    executionId ?: return@defer Mono.empty()
                    val commandMessage = ApplyExecutionSuccess(
                        id = executionId,
                        executeAt = System.currentTimeMillis()
                    ).toCommandMessage()
                    commandBus.send(commandMessage)
                }
            )
    }
}

class DomainEventCompensationFilter(commandBus: CommandBus) :
    EventCompensationFilter<DomainEventExchange<*>>(commandBus)

class StateEventCompensationFilter(commandBus: CommandBus) :
    EventCompensationFilter<StateEventExchange<*>>(commandBus)
