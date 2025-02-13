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

package me.ahoo.wow.openapi.command

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
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.ParameterRef.Companion.withParameter
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.withRequestTimeout
import me.ahoo.wow.openapi.ResponseRef.Companion.withTooManyRequests
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas
import me.ahoo.wow.openapi.Tags.toTags
import me.ahoo.wow.openapi.command.CommandRequestParameters.AGGREGATE_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.AGGREGATE_VERSION_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.LOCAL_FIRST_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.REQUEST_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_PROCESSOR_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_STAGE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_TIME_OUT_PARAMETER
import me.ahoo.wow.openapi.command.CommandResponses.BAD_REQUEST_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.COMMAND_RESULT_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.NOT_FOUND_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.VERSION_CONFLICT_RESPONSE
import me.ahoo.wow.openapi.route.AggregateRouteMetadata
import me.ahoo.wow.openapi.route.CommandRouteMetadata
import me.ahoo.wow.openapi.route.commandRouteMetadata
import me.ahoo.wow.serialization.MessageRecords

class CommandRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    val commandRouteMetadata: CommandRouteMetadata<*>,
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

    override val parameters: List<Parameter>
        get() {
            return buildList {
                addAll(super.parameters)
                add(WAIT_STAGE_PARAMETER.component)
                add(WAIT_CONTEXT_PARAMETER.ref)
                add(WAIT_PROCESSOR_PARAMETER.ref)
                add(WAIT_TIME_OUT_PARAMETER.ref)
                add(AGGREGATE_ID_PARAMETER.ref)
                add(AGGREGATE_VERSION_PARAMETER.ref)
                add(REQUEST_ID_PARAMETER.ref)
                add(LOCAL_FIRST_PARAMETER.ref)
                commandRouteMetadata.pathVariableMetadata.forEach { variableMetadata ->
                    withParameter(variableMetadata.variableName, ParameterIn.PATH, StringSchema()) {
                        it.required(variableMetadata.required)
                    }
                }
                commandRouteMetadata.headerVariableMetadata.forEach { variableMetadata ->
                    withParameter(variableMetadata.variableName, ParameterIn.HEADER, StringSchema()) {
                        it.required(variableMetadata.required)
                    }
                }
            }
        }
    override val requestBody: RequestBody = commandRouteMetadata.commandMetadata.commandType.toRequestBody()
    override val responses: ApiResponses
        get() = ApiResponses()
            .with(COMMAND_RESULT_RESPONSE)
            .with(BAD_REQUEST_RESPONSE)
            .with(NOT_FOUND_RESPONSE)
            .withRequestTimeout()
            .withTooManyRequests()
            .with(VERSION_CONFLICT_RESPONSE)
            .with(ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE)
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
            commandRouteMetadata = commandRouteMetadata
        )
    }

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<RouteSpec> {
        val aggregateMetadata = aggregateRouteMetadata.aggregateMetadata
        aggregateMetadata.state.aggregateType.toSchemaRef().schemas.mergeSchemas()
        return buildList {
            aggregateMetadata.command.registeredCommands
                .forEach { commandType ->
                    commandType.toCommandRouteSpec(currentContext, aggregateRouteMetadata)?.let {
                        it.commandRouteMetadata.commandMetadata.commandType.toSchemas().mergeSchemas()
                        add(it)
                    }
                }

            if (!aggregateMetadata.command.registeredDeleteAggregate) {
                DefaultDeleteAggregate::class.java.toCommandRouteSpec(currentContext, aggregateRouteMetadata)?.let {
                    it.commandRouteMetadata.commandMetadata.commandType.toSchemas().mergeSchemas()
                    add(it)
                }
            }
            if (!aggregateMetadata.command.registeredRecoverAggregate) {
                DefaultRecoverAggregate::class.java.toCommandRouteSpec(currentContext, aggregateRouteMetadata)?.let {
                    it.commandRouteMetadata.commandMetadata.commandType.toSchemas().mergeSchemas()
                    add(it)
                }
            }
        }
    }
}
