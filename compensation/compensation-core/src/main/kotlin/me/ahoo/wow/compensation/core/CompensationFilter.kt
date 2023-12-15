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

import me.ahoo.wow.api.annotation.ORDER_LAST
import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.messaging.processor.materialize
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.asCommandMessage
import me.ahoo.wow.compensation.api.CreateExecutionFailed
import me.ahoo.wow.compensation.api.ErrorDetails
import me.ahoo.wow.compensation.api.EventId.Companion.toEventId
import me.ahoo.wow.event.DomainEventDispatcher
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.exception.asErrorInfo
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.handler.Filter
import me.ahoo.wow.messaging.handler.FilterChain
import me.ahoo.wow.messaging.handler.FilterType
import me.ahoo.wow.projection.ProjectionDispatcher
import me.ahoo.wow.saga.stateless.StatelessSagaDispatcher
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

@FilterType(DomainEventDispatcher::class, StatelessSagaDispatcher::class, ProjectionDispatcher::class)
@Order(ORDER_LAST)
class CompensationFilter(private val commandBus: CommandBus) : Filter<DomainEventExchange<*>> {
    override fun filter(exchange: DomainEventExchange<*>, next: FilterChain<DomainEventExchange<*>>): Mono<Void> {
        return next.filter(exchange)
            // 失败： CreateExecutionFailed
            .onErrorResume {
                val eventFunction = exchange.getEventFunction() ?: return@onErrorResume it.toMono()
                //TODO get executionId
                val executionId = GlobalIdGenerator.generateAsString()
                val errorInfo = it.asErrorInfo()
                val createExecutionFailed = CreateExecutionFailed(
                    eventId = exchange.message.toEventId(),
                    processor = eventFunction.materialize(),
                    functionKind = eventFunction.functionKind,
                    error = ErrorDetails(
                        errorCode = errorInfo.errorCode,
                        errorMsg = errorInfo.errorMsg,
                        stackTrace = it.stackTraceToString()
                    ),
                    executionTime = System.currentTimeMillis()
                )
                val commandMessage = createExecutionFailed.asCommandMessage(executionId)
                commandBus.send(commandMessage).then(it.toMono())
            }
            // 成功：TODO
            .then(Mono.defer {
                Mono.empty<Void>()
            })
    }
}