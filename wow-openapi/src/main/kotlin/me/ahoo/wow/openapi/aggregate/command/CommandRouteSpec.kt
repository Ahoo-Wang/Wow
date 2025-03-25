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

package me.ahoo.wow.openapi.aggregate.command

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RequestBodyBuilder
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.aggregate.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.aggregate.AggregateRouteSpec
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.aggregateIdPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.aggregateVersionPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.localFirstPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.requestIdPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitContextPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitProcessorPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitStagePathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitTimeOutPathParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.badRequestCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.illegalAccessDeletedAggregateCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.notFoundCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.okCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.requestTimeoutCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.tooManyRequestsCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.versionConflictCommandResponse
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

class CommandRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    val commandRouteMetadata: CommandRouteMetadata<*>,
    override val componentContext: OpenAPIComponentContext
) : AggregateRouteSpec {

    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .operation(commandRouteMetadata.commandMetadata.name)
            .build()

    override val method: String
        get() {
            return commandRouteMetadata.method
        }

    override val accept: List<String>
        get() = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM)

    private fun CommandRoute.AppendPath.resolve(default: Boolean): Boolean {
        return when (this) {
            CommandRoute.AppendPath.DEFAULT -> {
                default
            }

            CommandRoute.AppendPath.ALWAYS -> true
            CommandRoute.AppendPath.NEVER -> false
        }
    }

    override val appendTenantPath: Boolean
        get() {
            val default = super.appendTenantPath
            return commandRouteMetadata.appendTenantPath.resolve(default)
        }

    override val appendOwnerPath: Boolean
        get() {
            val default = super.appendOwnerPath
            return commandRouteMetadata.appendOwnerPath.resolve(default)
        }

    override val appendIdPath: Boolean
        get() {
            if (aggregateRouteMetadata.owner == AggregateRoute.Owner.AGGREGATE_ID) {
                return false
            }
            val hasIdPathVariable = commandRouteMetadata.pathVariableMetadata
                .any { it.variableName == MessageRecords.ID }
            val default = hasIdPathVariable ||
                (
                    commandRouteMetadata.commandMetadata.aggregateIdGetter == null &&
                        !commandRouteMetadata.commandMetadata.isCreate
                    )
            return commandRouteMetadata.appendIdPath.resolve(default)
        }

    override val path: String
        get() {
            return PathBuilder()
                .append(commandRouteMetadata.prefix)
                .append(super.path)
                .append(commandRouteMetadata.action).build()
        }
    override val summary: String
        get() = commandRouteMetadata.summary
    override val description: String
        get() = commandRouteMetadata.description
    override val tags: List<String>
        get() {
            val tags = mutableListOf<String>()
            tags.addAll(super.tags)
            commandRouteMetadata.commandMetadata.commandType.toTags().let {
                tags.addAll(it)
            }
            return tags
        }

    override val parameters: List<Parameter> = buildList {
        addAll(super.parameters)
        add(componentContext.waitStagePathParameter())
        add(componentContext.waitContextPathParameter())
        add(componentContext.waitProcessorPathParameter())
        add(componentContext.waitTimeOutPathParameter())
        add(componentContext.aggregateIdPathParameter())
        add(componentContext.aggregateVersionPathParameter())
        add(componentContext.requestIdPathParameter())
        add(componentContext.localFirstPathParameter())
        commandRouteMetadata.pathVariableMetadata.forEach { variableMetadata ->
            Parameter()
                .name(variableMetadata.variableName)
                .`in`(ParameterIn.PATH.toString())
                .schema(StringSchema())
                .let { add(it) }
        }
        commandRouteMetadata.headerVariableMetadata.forEach { variableMetadata ->
            Parameter()
                .name(variableMetadata.variableName)
                .`in`(ParameterIn.HEADER.toString())
                .schema(StringSchema())
                .required(variableMetadata.required)
                .let {
                    add(it)
                }
        }
    }
    override val requestBody: RequestBody = RequestBodyBuilder().description(summary)
        .content(schema = componentContext.schema(commandRouteMetadata.commandMetadata.commandType)).build()
    override val responses: ApiResponses = ApiResponses().apply {
        addApiResponse(Https.Code.OK, componentContext.okCommandResponse())
        addApiResponse(Https.Code.BAD_REQUEST, componentContext.badRequestCommandResponse())
        addApiResponse(Https.Code.NOT_FOUND, componentContext.notFoundCommandResponse())
        addApiResponse(Https.Code.CONFLICT, componentContext.versionConflictCommandResponse())
        addApiResponse(Https.Code.TOO_MANY_REQUESTS, componentContext.tooManyRequestsCommandResponse())
        addApiResponse(Https.Code.REQUEST_TIMEOUT, componentContext.requestTimeoutCommandResponse())
        addApiResponse(Https.Code.GONE, componentContext.illegalAccessDeletedAggregateCommandResponse())
    }
}

class CommandRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    private fun Class<*>.toCommandRouteSpec(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): CommandRouteSpec? {
        val commandRouteMetadata = commandRouteMetadata()
        if (!commandRouteMetadata.enabled) {
            return null
        }
        return CommandRouteSpec(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandRouteMetadata = commandRouteMetadata,
            componentContext = componentContext
        )
    }

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<RouteSpec> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        return buildList {
            aggregateMetadata.command.registeredCommands
                .forEach { commandType ->
                    commandType.toCommandRouteSpec(currentContext, aggregateRouteMetadata)?.let {
                        add(it)
                    }
                }

            if (!aggregateMetadata.command.registeredDeleteAggregate) {
                DefaultDeleteAggregate::class.java.toCommandRouteSpec(currentContext, aggregateRouteMetadata)?.let {
                    add(it)
                }
            }
            if (!aggregateMetadata.command.registeredRecoverAggregate) {
                DefaultRecoverAggregate::class.java.toCommandRouteSpec(currentContext, aggregateRouteMetadata)?.let {
                    add(it)
                }
            }
        }
    }
}
