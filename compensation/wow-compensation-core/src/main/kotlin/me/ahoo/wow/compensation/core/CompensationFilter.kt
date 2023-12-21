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
import me.ahoo.wow.api.messaging.processor.materialize
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.toCommandMessage
import me.ahoo.wow.command.wait.EventHandledNotifierFilter
import me.ahoo.wow.command.wait.ProjectedNotifierFilter
import me.ahoo.wow.command.wait.SagaHandledNotifierFilter
import me.ahoo.wow.compensation.api.ApplyExecutionFailed
import me.ahoo.wow.compensation.api.ApplyExecutionSuccess
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId.Companion.toEventId
import me.ahoo.wow.compensation.api.RetrySpec.Companion.toSpec
import me.ahoo.wow.event.DomainEventDispatcher
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.exception.toErrorInfo
import me.ahoo.wow.messaging.compensation.CompensationMatcher.compensationId
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterType
import me.ahoo.wow.messaging.handler.RetryableFilter
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

@FilterType(DomainEventDispatcher::class, StatelessSagaDispatcher::class, ProjectionDispatcher::class)
@Order(
    ORDER_FIRST,
    after = [EventHandledNotifierFilter::class, SagaHandledNotifierFilter::class, ProjectedNotifierFilter::class],
    before = [RetryableFilter::class]
)
class CompensationFilter(private val commandBus: CommandBus) : Filter<DomainEventExchange<*>> {
    override fun filter(exchange: DomainEventExchange<*>, next: FilterChain<DomainEventExchange<*>>): Mono<Void> {
        val executionId = exchange.message.header.compensationId
        return next.filter(exchange)
            .onErrorResume {
                val eventFunction = exchange.getEventFunction() ?: return@onErrorResume it.toMono()
                val retry = eventFunction.getAnnotation(Retry::class.java)
                if (retry?.enabled == true) {
                    return@onErrorResume it.toMono()
                }
                val errorInfo = it.toErrorInfo()
                val errorDetails = ErrorDetails(
                    errorCode = errorInfo.errorCode,
                    errorMsg = errorInfo.errorMsg,
                    stackTrace = it.stackTraceToString()
                )
                val executionTime = System.currentTimeMillis()
                val command = if (executionId == null) {
                    CreateExecutionFailed(
                        eventId = exchange.message.toEventId(),
                        processor = eventFunction.materialize(),
                        functionKind = eventFunction.functionKind,
                        error = errorDetails,
                        executeAt = executionTime,
                        retrySpec = retry?.toSpec()
                    )
                } else {
                    ApplyExecutionFailed(id = executionId, error = errorDetails, executeAt = executionTime)
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
