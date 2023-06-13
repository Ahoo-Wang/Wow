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
import io.swagger.v3.oas.annotations.tags.Tag
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.infra.reflection.AnnotationScanner.scan
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.route.AggregateRoutePathSpec.Companion.asCommandAggregateRoutePathSpec
import me.ahoo.wow.route.CommandAggregateRoutePathSpec
import me.ahoo.wow.route.CommandRouteMetadata
import me.ahoo.wow.route.asCommandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords
import me.ahoo.wow.webflux.exception.ExceptionHandler
import me.ahoo.wow.webflux.route.CommandHandlerFunction
import me.ahoo.wow.webflux.route.DEFAULT_TIME_OUT
import org.springdoc.core.fn.builders.operation.Builder
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.RequestPredicates
import java.util.function.Consumer

class CommandRouteAppender(
    private val currentContext: NamedBoundedContext,
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val routerFunctionBuilder: SpringdocRouteBuilder,
    private val commandGateway: CommandGateway,
    private val exceptionHandler: ExceptionHandler
) {
    fun append() {
        aggregateMetadata.command.commandFunctionRegistry
            .forEach {
                val commandRouteMetadata =
                    it.key.asCommandRouteMetadata()
                appendCommandRoute(commandRouteMetadata)
            }
    }

    private fun appendCommandRoute(commandRouteMetadata: CommandRouteMetadata<out Any>) {
        if (!commandRouteMetadata.enabled) {
            return
        }
        val commandAggregateRoutePathSpec =
            commandRouteMetadata.asCommandAggregateRoutePathSpec(currentContext, aggregateMetadata)
        val commandRoutePath = commandAggregateRoutePathSpec.routePath
        when {
            commandRouteMetadata.commandMetadata.isCreate ->
                routerFunctionBuilder
                    .POST(
                        commandRoutePath,
                        RequestPredicates.accept(MediaType.APPLICATION_JSON),
                        CommandHandlerFunction(
                            aggregateMetadata = aggregateMetadata,
                            commandRouteMetadata = commandRouteMetadata,
                            commandGateway = commandGateway,
                            exceptionHandler = exceptionHandler,
                        ),
                        commandOperation(commandAggregateRoutePathSpec, aggregateMetadata),
                    )

            commandRouteMetadata.commandMetadata.isDelete -> {
                routerFunctionBuilder
                    .DELETE(
                        commandRoutePath,
                        RequestPredicates.accept(MediaType.APPLICATION_JSON),
                        CommandHandlerFunction(
                            aggregateMetadata = aggregateMetadata,
                            commandRouteMetadata = commandRouteMetadata,
                            commandGateway = commandGateway,
                            exceptionHandler = exceptionHandler,
                        ),
                        commandOperation(commandAggregateRoutePathSpec, aggregateMetadata),
                    )
            }

            else ->
                routerFunctionBuilder
                    .PUT(
                        commandRoutePath,
                        RequestPredicates.accept(MediaType.APPLICATION_JSON),
                        CommandHandlerFunction(
                            aggregateMetadata = aggregateMetadata,
                            commandRouteMetadata = commandRouteMetadata,
                            commandGateway = commandGateway,
                            exceptionHandler = exceptionHandler,
                        ),
                        commandOperation(commandAggregateRoutePathSpec, aggregateMetadata),
                    )
        }
    }

    @Suppress("LongMethod")
    private fun commandOperation(
        routePathSpec: CommandAggregateRoutePathSpec,
        aggregateMetadata: AggregateMetadata<*, *>
    ): Consumer<Builder> {
        return Consumer<Builder> {
            val commandMetadata = routePathSpec.commandRouteMetadata.commandMetadata
            commandMetadata
                .commandType.scan<Tag>()?.let { tag ->
                    it.tag(tag.name)
                    if (tag.description.isNotBlank()) {
                        it.description(tag.description)
                    }
                }

            if (routePathSpec.appendedTenantId) {
                it.parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(MessageRecords.TENANT_ID)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java)
                        .example(TenantId.DEFAULT_TENANT_ID),
                )
            }

            it
                .tag(aggregateMetadata.asStringWithAlias())
                .operationId("${aggregateMetadata.asStringWithAlias()}.${commandMetadata.name}")
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(CommandHeaders.WAIT_STAGE)
                        .`in`(ParameterIn.HEADER)
                        .example("${CommandStage.PROCESSED}")
                        .implementation(CommandStage::class.java),
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(CommandHeaders.WAIT_CONTEXT)
                        .`in`(ParameterIn.HEADER)
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(CommandHeaders.WAIT_PROCESSOR)
                        .`in`(ParameterIn.HEADER)
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(CommandHeaders.WAIT_TIME_OUT)
                        .`in`(ParameterIn.HEADER)
                        .example("${DEFAULT_TIME_OUT.toMillis()}")
                        .description("Unit: millisecond")
                        .implementation(Int::class.java),
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(CommandHeaders.AGGREGATE_VERSION)
                        .`in`(ParameterIn.HEADER)
                        .required(false)
                        .implementation(Int::class.java),
                )
                .parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(CommandHeaders.REQUEST_ID)
                        .`in`(ParameterIn.HEADER)
                        .required(false)
                        .implementation(String::class.java),
                )
                .requestBody(
                    org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder()
                        .required(true)
                        .implementation(commandMetadata.commandType),
                )
                .response(
                    org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder()
                        .responseCode(HttpStatus.OK.value().toString())
                        .description(HttpStatus.OK.reasonPhrase)
                        .implementation(CommandResult::class.java),
                )
            commandMetadata.commandType.scan<Summary>()?.let { summary ->
                it.summary(summary.value)
            }
            routePathSpec.commandRouteMetadata.pathVariableMetadata.forEach { pathVariableMetadata ->
                if (routePathSpec.appendedTenantId &&
                    pathVariableMetadata.pathVariableName == MessageRecords.TENANT_ID
                ) {
                    return@forEach
                }
                it.parameter(
                    org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder()
                        .name(pathVariableMetadata.pathVariableName)
                        .`in`(ParameterIn.PATH)
                        .implementation(String::class.java)
                        .required(pathVariableMetadata.required),
                )
            }
        }
    }
}
