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
import me.ahoo.wow.modeling.asNamedAggregateString
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateRepository
import me.ahoo.wow.route.AggregateRoutePathSpec.Companion.asAggregateIdRoutePathSpec
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.ExceptionHandler
import me.ahoo.wow.webflux.route.LoadAggregateHandlerFunction
import org.springdoc.core.fn.builders.operation.Builder
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import java.util.function.Consumer

class LoadAggregateRouteAppender(
    private val currentContext: NamedBoundedContext,
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val routerFunctionBuilder: SpringdocRouteBuilder,
    private val stateAggregateRepository: StateAggregateRepository,
    private val exceptionHandler: ExceptionHandler,
) {
    fun append() {
        val aggregateIdPath = aggregateMetadata.asAggregateIdRoutePathSpec(currentContext).routePath
        routerFunctionBuilder
            .GET(
                /* pattern = */ "$aggregateIdPath/state",
                /* predicate = */ RequestPredicates.accept(MediaType.APPLICATION_JSON),
                /* handlerFunction = */
                LoadAggregateHandlerFunction(
                    aggregateMetadata = aggregateMetadata,
                    stateAggregateRepository = stateAggregateRepository,
                    exceptionHandler = exceptionHandler,
                ),
                /* operationsConsumer = */ loadAggregateOperation(),
            )
    }

    private fun loadAggregateOperation(): Consumer<Builder> {
        return Consumer<Builder> {
            it
                .tag(Wow.WOW)
                .tag(aggregateMetadata.asNamedAggregateString())
                .summary("Load state aggregate")
                .operationId("${aggregateMetadata.asNamedAggregateString()}.loadStateAggregate")
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(MessageRecords.TENANT_ID)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java)
                        .example(DEFAULT_TENANT_ID),
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(RoutePaths.ID_KEY)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java),
                )
                .response(
                    org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                        .responseCode(HttpStatus.OK.value().toString())
                        .description(HttpStatus.OK.reasonPhrase)
                        .implementation(aggregateMetadata.state.aggregateType),
                )
        }
    }
}
