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
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.tags.Tag
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
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandCommonHeaderParameters
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.commandResponses
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.VariableMetadata
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
            val default =
                if (aggregateRouteMetadata.owner == AggregateRoute.Owner.AGGREGATE_ID && commandRouteMetadata.commandMetadata.isCreate) {
                    false
                } else {
                    super.appendOwnerPath
                }
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
        get() = commandRouteMetadata.summary.ifBlank { commandRouteMetadata.commandMetadata.name }
    override val description: String
        get() = commandRouteMetadata.description
    override val tags: List<Tag>
        get() {
            val tags = mutableListOf<Tag>()
            tags.addAll(super.tags)
            commandRouteMetadata.commandMetadata.commandType.toTags().let {
                tags.addAll(it)
            }
            return tags
        }

    private val pathParameters: List<Parameter> = commandRouteMetadata.pathVariableMetadata
        .filter {
            if (it.variableName == MessageRecords.ID) {
                return@filter appendIdPath.not()
            }
            if (it.variableName == MessageRecords.OWNER_ID) {
                return@filter appendOwnerPath.not()
            }
            if (it.variableName == MessageRecords.TENANT_ID) {
                return@filter appendTenantPath.not()
            }
            true
        }
        .map { variableMetadata ->
            Parameter()
                .name(variableMetadata.variableName)
                .`in`(ParameterIn.PATH.toString())
                .schema(variableMetadata.variableSchema())
        }

    private fun VariableMetadata.variableSchema(): Schema<*> {
        return variableType?.let {
            componentContext.schema(it)
        } ?: StringSchema()
    }

    private val headerParameters: List<Parameter> =
        commandRouteMetadata.headerVariableMetadata.map { variableMetadata ->
            Parameter()
                .name(variableMetadata.variableName)
                .`in`(ParameterIn.HEADER.toString())
                .schema(variableMetadata.variableSchema())
                .required(variableMetadata.required)
        }

    override val parameters: List<Parameter> = buildList {
        addAll(super.parameters)
        addAll(pathParameters)
        addAll(headerParameters)
        addAll(componentContext.commandCommonHeaderParameters())
    }
    override val requestBody: RequestBody = RequestBodyBuilder().description(summary)
        .content(schema = componentContext.schema(commandRouteMetadata.commandMetadata.commandType)).build()
    override val responses: ApiResponses = componentContext.commandResponses()
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
