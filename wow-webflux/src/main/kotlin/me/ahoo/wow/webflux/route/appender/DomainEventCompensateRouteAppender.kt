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

package me.ahoo.wow.webflux.route.appender

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.compensation.DomainEventCompensateHandlerFunction
import me.ahoo.wow.webflux.route.compensation.EventCompensateHandlerFunction
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder

class DomainEventCompensateRouteAppender(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
    override val routerFunctionBuilder: SpringdocRouteBuilder,
    override val eventCompensator: DomainEventCompensator,
    override val exceptionHandler: ExceptionHandler
) : EventCompensateRouteAppender() {

    override val topicKind: String
        get() = "event"

    override fun createEventCompensateHandlerFunction(): EventCompensateHandlerFunction {
        return DomainEventCompensateHandlerFunction(
            aggregateMetadata = aggregateMetadata,
            eventCompensator = eventCompensator,
            exceptionHandler = exceptionHandler,
        )
    }
}
