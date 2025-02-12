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

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.ObjectSchema
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
import me.ahoo.wow.openapi.command.CommandFacadeRouteSpecFactory.Companion.PATH
import me.ahoo.wow.openapi.command.CommandRequestParameters.AGGREGATE_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.AGGREGATE_VERSION_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_AGGREGATE_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_AGGREGATE_NAME_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.COMMAND_TYPE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.LOCAL_FIRST_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.OWNER_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.REQUEST_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.TENANT_ID_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_CONTEXT_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_PROCESSOR_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_STAGE_PARAMETER
import me.ahoo.wow.openapi.command.CommandRequestParameters.WAIT_TIME_OUT_PARAMETER
import me.ahoo.wow.openapi.command.CommandResponses.BAD_REQUEST_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.COMMAND_RESULT_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.ILLEGAL_ACCESS_DELETED_AGGREGATE_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.NOT_FOUND_RESPONSE
import me.ahoo.wow.openapi.command.CommandResponses.VERSION_CONFLICT_RESPONSE
import me.ahoo.wow.openapi.toJsonContent

object CommandFacadeRouteSpec : RouteSpec {
    override val id: String = RouteIdSpec()
        .prefix(Wow.WOW)
        .resourceName("command")
        .operation("send")
        .build()
    override val path: String
        get() = PATH
    override val method: String
        get() = Https.Method.POST
    override val summary: String = "send command"
    override val parameters: List<Parameter>
        get() {
            return buildList {
                add(COMMAND_TYPE_PARAMETER.ref)
                add(WAIT_STAGE_PARAMETER.component)
                add(WAIT_CONTEXT_PARAMETER.ref)
                add(WAIT_PROCESSOR_PARAMETER.ref)
                add(WAIT_TIME_OUT_PARAMETER.ref)
                add(TENANT_ID_PARAMETER.ref)
                add(OWNER_ID_PARAMETER.ref)
                add(AGGREGATE_ID_PARAMETER.ref)
                add(AGGREGATE_VERSION_PARAMETER.ref)
                add(REQUEST_ID_PARAMETER.ref)
                add(LOCAL_FIRST_PARAMETER.ref)
                add(COMMAND_AGGREGATE_CONTEXT_PARAMETER.ref)
                add(COMMAND_AGGREGATE_NAME_PARAMETER.ref)
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
        const val PATH = "/${Wow.WOW}/command/send"
    }

    override val components: Components = createComponents()

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(CommandFacadeRouteSpec)
    }
}
