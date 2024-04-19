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
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.ComponentRef.Companion.createComponents
import me.ahoo.wow.openapi.GlobalRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.withRequestTimeout
import me.ahoo.wow.openapi.ResponseRef.Companion.withTooManyRequests
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.command.CommandFacadeRouteSpecFactory.Companion.COMMAND_AGGREGATE_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandFacadeRouteSpecFactory.Companion.COMMAND_AGGREGATE_NAME_PARAMETER
import me.ahoo.wow.openapi.command.CommandFacadeRouteSpecFactory.Companion.COMMAND_TYPE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.AGGREGATE_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.AGGREGATE_VERSION_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.BAD_REQUEST_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.COMMAND_RESULT_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.NOT_FOUND_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.REQUEST_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.VERSION_CONFLICT_RESPONSE
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_PROCESSOR_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_STAGE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRouteSpecFactory.Companion.WAIT_TIME_OUT_PARAMETER
import me.ahoo.wow.openapi.toJsonContent

class CommandFacadeRouteSpec(
    override val id: String,
    override val path: String,
    override val method: String,
    override val summary: String,
) : RouteSpec {
    override val parameters: List<Parameter>
        get() {
            return buildList {
                add(WAIT_STAGE_PARAMETER.component)
                add(WAIT_CONTEXT_PARAMETER.ref)
                add(WAIT_PROCESSOR_PARAMETER.ref)
                add(WAIT_TIME_OUT_PARAMETER.ref)
                add(AGGREGATE_ID_PARAMETER.ref)
                add(AGGREGATE_VERSION_PARAMETER.ref)
                add(REQUEST_ID_PARAMETER.ref)
                add(COMMAND_AGGREGATE_CONTEXT_PARAMETER)
                add(COMMAND_AGGREGATE_NAME_PARAMETER)
                add(COMMAND_TYPE_PARAMETER)
            }
        }
    override val requestBody: RequestBody = RequestBody()
        .required(true)
        .content(
            ObjectSchema().toJsonContent()
        )
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

class CommandFacadeRouteSpecFactory : GlobalRouteSpecFactory {
    companion object {
        val COMMAND_TYPE_PARAMETER: Parameter = Parameter()
            .name(CommandHeaders.COMMAND_TYPE)
            .required(true)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema())
            .description("Command Body Class Type")
        val COMMAND_AGGREGATE_CONTEXT_PARAMETER: Parameter = Parameter()
            .name(CommandHeaders.COMMAND_AGGREGATE_CONTEXT)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema())
            .description("Command Aggregate Context")
        val COMMAND_AGGREGATE_NAME_PARAMETER: Parameter = Parameter()
            .name(CommandHeaders.COMMAND_AGGREGATE_NAME)
            .`in`(ParameterIn.HEADER.toString())
            .schema(StringSchema())
            .description("Command Aggregate Name")
        val ID = RouteIdSpec()
            .prefix(Wow.WOW)
            .resourceName("command")
            .operation("send")
            .build()

        const val PATH = "/${Wow.WOW}/command/send"
        const val METHOD = Https.Method.POST
        const val SUMMARY = "send command"
    }

    override val components: Components = createComponents()

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(
            CommandFacadeRouteSpec(
                ID,
                PATH,
                METHOD,
                SUMMARY
            )
        )
    }
}
