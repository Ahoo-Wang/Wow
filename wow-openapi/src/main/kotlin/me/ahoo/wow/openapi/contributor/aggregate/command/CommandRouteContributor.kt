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

package me.ahoo.wow.openapi.contributor.aggregate.command

import me.ahoo.wow.api.abac.DefaultApplyResourceTags
import me.ahoo.wow.api.annotation.AggregateRoute
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.aggregate.command.CommandRouteSpec
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.HttpTag
import me.ahoo.wow.openapi.contributor.aggregate.aggregateParameters
import me.ahoo.wow.openapi.contributor.aggregate.aggregatePath
import me.ahoo.wow.openapi.contributor.aggregate.aggregateTags
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendOwnerPath
import me.ahoo.wow.openapi.contributor.aggregate.defaultAppendTenantPath
import me.ahoo.wow.openapi.contributor.commandCommonHeaderParameterRefs
import me.ahoo.wow.openapi.contributor.commandResponseRefs
import me.ahoo.wow.openapi.contributor.schemaRef
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.VariableMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

object CommandRouteContributor : RouteContributor {
    override val id: String = "aggregate.command"
    override val category: RouteCategory = RouteCategory.COMMAND
    override val order: Int = 100

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        return buildList {
            aggregateMetadata.command.registeredCommands.forEach { commandType ->
                commandType.toCommandRouteContract(currentContext, aggregateRouteMetadata, componentContext)
                    ?.let(::add)
            }
            if (!aggregateMetadata.command.registeredDeleteAggregate) {
                DefaultDeleteAggregate::class.java
                    .toCommandRouteContract(currentContext, aggregateRouteMetadata, componentContext)
                    ?.let(::add)
            }
            if (!aggregateMetadata.command.registeredRecoverAggregate) {
                DefaultRecoverAggregate::class.java
                    .toCommandRouteContract(currentContext, aggregateRouteMetadata, componentContext)
                    ?.let(::add)
            }
            if (!aggregateMetadata.command.registeredApplyResourceTags) {
                DefaultApplyResourceTags::class.java
                    .toCommandRouteContract(currentContext, aggregateRouteMetadata, componentContext)
                    ?.let(::add)
            }
        }
    }

    private fun Class<*>.toCommandRouteContract(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): HttpRouteContract? {
        val commandRouteMetadata = commandRouteMetadata()
        if (!commandRouteMetadata.enabled) {
            return null
        }
        return CommandRouteContractFactory(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            commandRouteMetadata = commandRouteMetadata,
            componentContext = componentContext
        ).create()
    }
}

private class CommandRouteContractFactory(
    private val currentContext: NamedBoundedContext,
    private val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    private val commandRouteMetadata: CommandRouteMetadata<*>,
    private val componentContext: OpenAPIComponentContext
) {
    private val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata

    fun create(): HttpRouteContract {
        return HttpRouteContract(
            routeId = RouteIdSpec()
                .aggregate(aggregateMetadata)
                .operation(commandRouteMetadata.commandMetadata.name)
                .build(),
            method = commandRouteMetadata.method,
            path = commandPath(),
            handlerKey = CommandRouteSpec::class.java.name,
            summary = summary(),
            description = commandRouteMetadata.description,
            accept = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM),
            produce = listOf(Https.MediaType.APPLICATION_JSON, Https.MediaType.TEXT_EVENT_STREAM),
            parameters = parameters(),
            requestBody = requestBody(),
            responses = componentContext.commandResponseRefs(),
            tags = tags(),
            handlerMetadata = HttpRouteHandlerMetadata.Command(
                aggregateRouteMetadata = aggregateRouteMetadata,
                commandRouteMetadata = commandRouteMetadata
            )
        )
    }

    private fun commandPath(): String {
        return PathBuilder()
            .append(commandRouteMetadata.prefix)
            .append(
                aggregatePath(
                    currentContext = currentContext,
                    aggregateRouteMetadata = aggregateRouteMetadata,
                    appendTenantPath = appendTenantPath(),
                    appendOwnerPath = appendOwnerPath(),
                    appendIdPath = appendIdPath()
                )
            )
            .append(commandRouteMetadata.action)
            .build()
    }

    private fun summary(): String {
        return commandRouteMetadata.summary.ifBlank { commandRouteMetadata.commandMetadata.name }
    }

    private fun parameters(): List<HttpParameter> {
        return buildList {
            addAll(
                componentContext.aggregateParameters(
                    aggregateRouteMetadata = aggregateRouteMetadata,
                    appendTenantPath = appendTenantPath(),
                    appendOwnerPath = appendOwnerPath(),
                    appendIdPath = appendIdPath()
                )
            )
            addAll(pathVariableParameters())
            addAll(headerVariableParameters())
            addAll(componentContext.commandCommonHeaderParameterRefs())
        }
    }

    private fun pathVariableParameters(): List<HttpParameter> {
        return commandRouteMetadata.pathVariableMetadata
            .filter { variableMetadata ->
                when (variableMetadata.variableName) {
                    MessageRecords.ID -> appendIdPath().not()
                    MessageRecords.OWNER_ID -> appendOwnerPath().not()
                    MessageRecords.TENANT_ID -> appendTenantPath().not()
                    else -> true
                }
            }
            .map { variableMetadata ->
                HttpParameter(
                    name = variableMetadata.variableName,
                    location = HttpParameterLocation.PATH,
                    required = true,
                    schema = variableMetadata.schema()
                )
            }
    }

    private fun headerVariableParameters(): List<HttpParameter> {
        return commandRouteMetadata.headerVariableMetadata.map { variableMetadata ->
            HttpParameter(
                name = variableMetadata.variableName,
                location = HttpParameterLocation.HEADER,
                required = variableMetadata.required,
                schema = variableMetadata.schema()
            )
        }
    }

    private fun VariableMetadata.schema(): HttpSchema {
        return variableType?.let(::schemaRef) ?: HttpSchema.String
    }

    private fun requestBody(): HttpRequestBody {
        return HttpRequestBody(
            description = summary(),
            content = listOf(
                HttpContent(
                    Https.MediaType.APPLICATION_JSON,
                    schemaRef(commandRouteMetadata.commandMetadata.commandType)
                )
            )
        )
    }

    private fun tags(): List<HttpTag> {
        return buildList {
            addAll(aggregateTags(aggregateMetadata))
            commandRouteMetadata.commandMetadata.commandType.toTags().forEach { tag ->
                add(HttpTag(tag.name, tag.description))
            }
        }
    }

    private fun CommandRoute.AppendPath.resolve(default: Boolean): Boolean {
        return when (this) {
            CommandRoute.AppendPath.DEFAULT -> default
            CommandRoute.AppendPath.ALWAYS -> true
            CommandRoute.AppendPath.NEVER -> false
        }
    }

    private fun appendTenantPath(): Boolean {
        return commandRouteMetadata.appendTenantPath.resolve(aggregateRouteMetadata.defaultAppendTenantPath())
    }

    private fun appendOwnerPath(): Boolean {
        val default = if (
            aggregateRouteMetadata.owner == AggregateRoute.Owner.AGGREGATE_ID &&
            commandRouteMetadata.commandMetadata.isCreate
        ) {
            false
        } else {
            aggregateRouteMetadata.defaultAppendOwnerPath()
        }
        return commandRouteMetadata.appendOwnerPath.resolve(default)
    }

    private fun appendIdPath(): Boolean {
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
}
