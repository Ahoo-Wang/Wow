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

import io.swagger.v3.oas.annotations.enums.ParameterIn
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.modeling.TenantId.Companion.DEFAULT_TENANT_ID
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.messaging.compensation.EventCompensator
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.route.AggregateRoutePathSpec.Companion.asAggregateIdRoutePathSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.appender.RoutePaths.COMPENSATE_HEAD_VERSION_KEY
import me.ahoo.wow.webflux.route.appender.RoutePaths.COMPENSATE_TAIL_VERSION_KEY
import me.ahoo.wow.webflux.route.compensation.EventCompensateHandlerFunction
import org.springdoc.core.fn.builders.operation.Builder
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import java.util.function.Consumer

abstract class EventCompensateRouteAppender {

    abstract val currentContext: NamedBoundedContext
    abstract val aggregateMetadata: AggregateMetadata<*, *>
    abstract val routerFunctionBuilder: SpringdocRouteBuilder
    abstract val eventCompensator: EventCompensator
    abstract val exceptionHandler: ExceptionHandler
    abstract val topicKind: String

    abstract fun createEventCompensateHandlerFunction(): EventCompensateHandlerFunction

    fun append() {
        val aggregateIdPath = aggregateMetadata.asAggregateIdRoutePathSpec(currentContext).routePath
        routerFunctionBuilder
            .PUT(
                /* pattern = */
                "$aggregateIdPath/$topicKind/{$COMPENSATE_HEAD_VERSION_KEY}/{$COMPENSATE_TAIL_VERSION_KEY}/compensate",
                /* predicate = */
                RequestPredicates.accept(MediaType.APPLICATION_JSON),
                /* handlerFunction = */
                createEventCompensateHandlerFunction(),
                /* operationsConsumer = */
                eventCompensateOperation(),
            )
    }

    @Suppress("LongMethod")
    private fun eventCompensateOperation(): Consumer<Builder> {
        return Consumer<Builder> {
            it
                .tag(Wow.WOW)
                .tag(aggregateMetadata.asStringWithAlias())
                .summary("$topicKind compensate")
                .operationId("${aggregateMetadata.asStringWithAlias()}.${topicKind}Compensate")
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(MessageRecords.TENANT_ID)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java)
                        .example(DEFAULT_TENANT_ID),
                ).parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(RoutePaths.ID_KEY)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java),
                ).parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(COMPENSATE_HEAD_VERSION_KEY)
                        .`in`(ParameterIn.PATH)
                        .implementation(Int::class.java)
                        .example(EventStore.DEFAULT_HEAD_VERSION.toString()),
                ).parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(COMPENSATE_TAIL_VERSION_KEY)
                        .`in`(ParameterIn.PATH)
                        .implementation(Int::class.java)
                        .example(Int.MAX_VALUE.toString()),
                )
                .requestBody(
                    org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder()
                        .required(true)
                        .implementation(CompensationConfig::class.java),
                )
                .response(
                    org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                        .responseCode(HttpStatus.OK.value().toString())
                        .description("Number of event streams compensated")
                        .implementation(Long::class.java),
                )
        }
    }
}
