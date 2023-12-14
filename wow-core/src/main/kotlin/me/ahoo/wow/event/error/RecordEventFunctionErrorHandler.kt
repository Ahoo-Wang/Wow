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

package me.ahoo.wow.event.error

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.messaging.processor.ProcessorInfoData
import me.ahoo.wow.api.messaging.processor.materialize
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.exception.asErrorInfo
import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.messaging.handler.ErrorHandler
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

fun interface EventFunctionErrorRepository {
    fun record(eventFunctionError: EventFunctionError): Mono<Void>
}

data class EventFunctionError(
    override val id: String,
    /**
     * event id.
     */
    val eventId: EventId,
    val functionKind: FunctionKind,
    val processor: ProcessorInfoData,
    val error: ErrorDetails,
    val createTime: Long,
) : Identifier

data class EventId(override val id: String, override val aggregateId: AggregateId) : Identifier, AggregateIdCapable
data class ErrorDetails(override val errorCode: String, override val errorMsg: String, val stackTrace: String) :
    ErrorInfo

class RecordEventFunctionErrorHandler(private val reporter: EventFunctionErrorRepository) :
    ErrorHandler<DomainEventExchange<*>> {
    companion object {
        private val log = LoggerFactory.getLogger(RecordEventFunctionErrorHandler::class.java)
    }

    override fun handle(exchange: DomainEventExchange<*>, throwable: Throwable): Mono<Void> {
        if (log.isErrorEnabled) {
            log.error(throwable.message, throwable)
        }
        val eventFunction = requireNotNull(exchange.getEventFunction())
        val errorInfo = throwable.asErrorInfo()
        val eventFunctionError = EventFunctionError(
            id = GlobalIdGenerator.generateAsString(),
            eventId = EventId(id = exchange.message.id, aggregateId = exchange.message.aggregateId),
            functionKind = eventFunction.functionKind,
            processor = eventFunction.materialize(),
            error = ErrorDetails(
                errorCode = errorInfo.errorCode,
                errorMsg = errorInfo.errorMsg,
                stackTrace = throwable.stackTraceToString()
            ),
            createTime = System.currentTimeMillis()
        )
        return reporter.record(eventFunctionError)
    }
}
