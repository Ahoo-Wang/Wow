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
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.command.DefaultDeleteAggregate
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.CommandResult
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ParameterRef
import me.ahoo.wow.openapi.ParameterRef.Companion.with
import me.ahoo.wow.openapi.ParameterRef.Companion.withParameter
import me.ahoo.wow.openapi.PathBuilder
import me.ahoo.wow.openapi.RequestBodyRef.Companion.asRequestBody
import me.ahoo.wow.openapi.ResponseRef
import me.ahoo.wow.openapi.ResponseRef.Companion.asResponse
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.asSchemaRef
import me.ahoo.wow.openapi.SchemaRef.Companion.asSchemas
import me.ahoo.wow.openapi.Tags.asTags
import me.ahoo.wow.openapi.asJsonContent
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.AGGREGATE_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.AGGREGATE_VERSION_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.BAD_REQUEST_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.COMMAND_RESULT_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.NOT_FOUND_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.REQUEST_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.REQUEST_TIMEOUT_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.TOO_MANY_REQUESTS_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.VERSION_CONFLICT_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_PROCESSOR_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_STAGE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_TIME_OUT_PARAMETER
import me.ahoo.wow.openapi.route.CommandRouteMetadata
import me.ahoo.wow.openapi.route.asCommandRouteMetadata

class CommandRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
    val commandRouteMetadata: CommandRouteMetadata<*>,
) : AggregateRouteSpec {

    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.${commandRouteMetadata.commandMetadata.name}"
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
    override val appendIdPath: Boolean
        get() {
            val default = commandRouteMetadata.commandMetadata.aggregateIdGetter == null &&
                    !commandRouteMetadata.commandMetadata.isCreate
            return commandRouteMetadata.appendIdPath.resolve(default)
        }

    override val path: String
        get() {
            if (commandRouteMetadata.ignoreAggregateNamePrefix) {
                return PathBuilder().append(commandRouteMetadata.prefix).append(commandRouteMetadata.path).build()
            }
            return PathBuilder()
                .append(commandRouteMetadata.prefix)
                .append(super.path)
                .append(commandRouteMetadata.path).build()
        }
    override val summary: String
        get() = commandRouteMetadata.summary
    override val description: String
        get() = commandRouteMetadata.description
    override val tags: List<String>
        get() {
            val tags = mutableListOf<String>()
            tags.addAll(super.tags)
            commandRouteMetadata.commandMetadata.commandType.asTags().let {
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
    override val requestBody: RequestBody = commandRouteMetadata.commandMetadata.commandType.asRequestBody()
    override val responses: ApiResponses
        get() = ApiResponses()
            .with(COMMAND_RESULT_RESPONSE)
            .with(BAD_REQUEST_RESPONSE)
            .with(NOT_FOUND_RESPONSE)
            .with(REQUEST_TIMEOUT_RESPONSE)
            .with(TOO_MANY_REQUESTS_RESPONSE)
            .with(VERSION_CONFLICT_RESPONSE)
            .with(ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE)
}

class CommandRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    companion object {
        val COMMAND_STAGE_SCHEMA = CommandStage::class.java.asSchemaRef(CommandStage.PROCESSED.name)
        val WAIT_STAGE_PARAMETER = Parameter()
            .name(CommandHeaders.WAIT_STAGE)
            .`in`(ParameterIn.HEADER.toString())
            .schema(COMMAND_STAGE_SCHEMA.ref).let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val WAIT_CONTEXT_PARAMETER = Parameter()
            .name(CommandHeaders.WAIT_CONTEXT)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema()).let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val WAIT_PROCESSOR_PARAMETER = Parameter()
            .name(CommandHeaders.WAIT_PROCESSOR)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema()).let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val WAIT_TIME_OUT_PARAMETER = Parameter()
            .name(CommandHeaders.WAIT_TIME_OUT)
            .`in`(ParameterIn.HEADER.toString())
            .schema(IntegerSchema())
            .description("Unit: millisecond").let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val AGGREGATE_ID_PARAMETER = Parameter()
            .name(CommandHeaders.AGGREGATE_ID)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema()).let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val AGGREGATE_VERSION_PARAMETER = Parameter()
            .name(CommandHeaders.AGGREGATE_VERSION)
            .`in`(ParameterIn.HEADER.toString())
            .schema(IntegerSchema()).let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val REQUEST_ID_PARAMETER = Parameter()
            .name(CommandHeaders.REQUEST_ID)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema()).let {
                ParameterRef("${Wow.WOW_PREFIX}${it.name}", it)
            }
        val COMMAND_RESULT_CONTENT = CommandResult::class.java.asSchemaRef().ref.asJsonContent()
        val COMMAND_RESULT_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}CommandResult",
            component = COMMAND_RESULT_CONTENT.asResponse(),
            code = Https.Code.OK
        )
        val BAD_REQUEST_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}CommandBadRequest",
            component = COMMAND_RESULT_CONTENT.asResponse(description = "Bad Request"),
            code = Https.Code.BAD_REQUEST
        )
        val NOT_FOUND_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}CommandNotFound",
            component = COMMAND_RESULT_CONTENT.asResponse("Not Found"),
            code = Https.Code.NOT_FOUND
        )
        val REQUEST_TIMEOUT_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}CommandRequestTimeout",
            component = COMMAND_RESULT_CONTENT.asResponse("Request Timeout"),
            code = Https.Code.REQUEST_TIMEOUT
        )
        val TOO_MANY_REQUESTS_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}CommandTooManyRequests",
            component = COMMAND_RESULT_CONTENT.asResponse("Too Many Requests"),
            code = Https.Code.TOO_MANY_REQUESTS
        )
        val VERSION_CONFLICT_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}VersionConflict",
            component = COMMAND_RESULT_CONTENT.asResponse(description = "Version Conflict"),
            code = Https.Code.CONFLICT
        )
        val ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE = ResponseRef(
            name = "${Wow.WOW_PREFIX}IllegalAccessDeletedAggregate",
            component = COMMAND_RESULT_CONTENT.asResponse(description = "Illegal Access Deleted Aggregate"),
            code = Https.Code.GONE
        )
    }

    init {
        COMMAND_STAGE_SCHEMA.schemas.mergeSchemas()
        CommandResult::class.java.asSchemas().mergeSchemas()
        components.parameters
            .with(WAIT_STAGE_PARAMETER)
            .with(WAIT_CONTEXT_PARAMETER)
            .with(WAIT_PROCESSOR_PARAMETER)
            .with(WAIT_TIME_OUT_PARAMETER)
            .with(AGGREGATE_ID_PARAMETER)
            .with(AGGREGATE_VERSION_PARAMETER)
            .with(REQUEST_ID_PARAMETER)

        components.responses
            .with(COMMAND_RESULT_RESPONSE)
            .with(BAD_REQUEST_RESPONSE)
            .with(NOT_FOUND_RESPONSE)
            .with(REQUEST_TIMEOUT_RESPONSE)
            .with(TOO_MANY_REQUESTS_RESPONSE)
            .with(VERSION_CONFLICT_RESPONSE)
            .with(ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE)
    }

    private fun Class<*>.asCommandRouteSpec(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): CommandRouteSpec? {
        val commandRouteMetadata = asCommandRouteMetadata()
        if (!commandRouteMetadata.enabled) {
            return null
        }
        return CommandRouteSpec(
            currentContext = currentContext,
            aggregateMetadata = aggregateMetadata,
            commandRouteMetadata = commandRouteMetadata
        )
    }

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<RouteSpec> {
        aggregateMetadata.state.aggregateType.asSchemaRef().schemas.mergeSchemas()
        return buildList {
            aggregateMetadata.command.commandFunctionRegistry
                .forEach { entry ->
                    entry.key.asCommandRouteSpec(currentContext, aggregateMetadata)?.let {
                        it.commandRouteMetadata.commandMetadata.commandType.asSchemas().mergeSchemas()
                        add(it)
                    }
                }
            if (!aggregateMetadata.command.registeredDeleteAggregate) {
                DefaultDeleteAggregate::class.java.asCommandRouteSpec(currentContext, aggregateMetadata)?.let {
                    it.commandRouteMetadata.commandMetadata.commandType.asSchemas().mergeSchemas()
                    add(it)
                }
            }
        }
    }
}
