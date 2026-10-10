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
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.OwnerPolicy
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.command.DefaultRecoverAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.component.CommandComponents
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpParameter
import me.ahoo.wow.openapi.contract.HttpParameterLocation
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpRouteHandlerMetadata
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.HttpTag
import me.ahoo.wow.openapi.contributor.aggregate.AggregateRouteScope
import me.ahoo.wow.openapi.contributor.aggregate.STREAMING_ACCEPT
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata
import me.ahoo.wow.openapi.metadata.CommandRouteMetadata
import me.ahoo.wow.openapi.metadata.VariableMetadata
import me.ahoo.wow.openapi.metadata.commandRouteMetadata
import me.ahoo.wow.rest.RouteVariables

object CommandRouteContributor : RouteContributor {
    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> {
        val scope = AggregateRouteScope(currentContext, aggregateRouteMetadata)
        val command = scope.aggregateMetadata.command
        val commandTypes = buildList {
            addAll(command.registeredCommands)
            if (!command.registeredDeleteAggregate) {
                add(DefaultDeleteAggregate::class.java)
            }
            if (!command.registeredRecoverAggregate) {
                add(DefaultRecoverAggregate::class.java)
            }
            if (!command.registeredApplyResourceTags) {
                add(DefaultApplyResourceTags::class.java)
            }
        }
        return commandTypes.map { it.commandRouteMetadata() }
            .filter { it.enabled }
            .map { CommandRouteContractFactory(scope, it).create() }
    }
}

private class CommandRouteContractFactory(
    private val scope: AggregateRouteScope,
    private val commandRouteMetadata: CommandRouteMetadata<*>
) {
    private val aggregateRouteMetadata = scope.aggregateRouteMetadata
    private val appendTenantPath = resolveAppendTenantPath()
    private val appendOwnerPath = resolveAppendOwnerPath()
    private val appendIdPath = resolveAppendIdPath()

    fun create(): HttpRouteContract {
        return HttpRouteContract(
            routeId = RouteIdSpec()
                .aggregate(scope.aggregateMetadata)
                .operation(commandRouteMetadata.commandMetadata.name)
                .build(),
            method = commandRouteMetadata.method,
            path = commandPath(),
            handlerKey = BuiltInHttpRouteHandlerKeys.Command.COMMAND,
            summary = summary(),
            description = commandRouteMetadata.description,
            accept = STREAMING_ACCEPT,
            parameters = parameters(),
            requestBody = requestBody(),
            responses = CommandComponents.responses,
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
            .append(scope.path(appendTenantPath, appendOwnerPath, appendIdPath))
            .append(commandRouteMetadata.action)
            .build()
    }

    private fun summary(): String {
        return commandRouteMetadata.summary.ifBlank { commandRouteMetadata.commandMetadata.name }
    }

    private fun parameters(): List<HttpParameter> {
        return buildList {
            addAll(scope.parameters(appendTenantPath, appendOwnerPath, appendIdPath))
            addAll(pathVariableParameters())
            addAll(headerVariableParameters())
            addAll(CommandComponents.commonHeaderParameters)
        }
    }

    private fun pathVariableParameters(): List<HttpParameter> {
        return commandRouteMetadata.pathVariableMetadata
            .filter { variableMetadata ->
                when (variableMetadata.variableName) {
                    RouteVariables.ID -> appendIdPath.not()
                    RouteVariables.OWNER_ID -> appendOwnerPath.not()
                    RouteVariables.TENANT_ID -> appendTenantPath.not()
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
        return variableType?.let { HttpSchema.TypeRef(it) } ?: HttpSchema.String
    }

    private fun requestBody(): HttpRequestBody {
        return HttpRequestBody(
            description = summary(),
            content = listOf(
                HttpContent(
                    Https.MediaType.APPLICATION_JSON,
                    HttpSchema.TypeRef(commandRouteMetadata.commandMetadata.commandType)
                )
            )
        )
    }

    private fun tags(): List<HttpTag> {
        return buildList {
            addAll(scope.tags)
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

    private fun resolveAppendTenantPath(): Boolean {
        return commandRouteMetadata.appendTenantPath.resolve(scope.defaultAppendTenantPath)
    }

    private fun resolveAppendOwnerPath(): Boolean {
        val default = if (
            aggregateRouteMetadata.ownerPolicy == OwnerPolicy.AGGREGATE_ID &&
            commandRouteMetadata.commandMetadata.isCreate
        ) {
            false
        } else {
            scope.defaultAppendOwnerPath
        }
        return commandRouteMetadata.appendOwnerPath.resolve(default)
    }

    private fun resolveAppendIdPath(): Boolean {
        if (aggregateRouteMetadata.ownerPolicy == OwnerPolicy.AGGREGATE_ID) {
            return false
        }
        val hasIdPathVariable = commandRouteMetadata.pathVariableMetadata
            .any { it.variableName == RouteVariables.ID }
        val default = hasIdPathVariable ||
            (
                commandRouteMetadata.commandMetadata.aggregateIdGetter == null &&
                    !commandRouteMetadata.commandMetadata.isCreate
                )
        return commandRouteMetadata.appendIdPath.resolve(default)
    }
}
