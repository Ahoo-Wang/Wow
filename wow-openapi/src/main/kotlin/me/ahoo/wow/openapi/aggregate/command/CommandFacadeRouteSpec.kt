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

import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.AbstractRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyBuilder
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.aggregateIdHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.aggregateVersionHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandAggregateContextHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandAggregateNameHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandTypeHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.localFirstHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.ownerIdHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.requestIdHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.tenantIdHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitContextHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitProcessorHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitStageHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitTimeOutHeaderParameter
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.badRequestCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.illegalAccessDeletedAggregateCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.notFoundCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.okCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.requestTimeoutCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.tooManyRequestsCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.versionConflictCommandResponse
import me.ahoo.wow.openapi.aggregate.command.CommandFacadeRouteSpecFactory.Companion.PATH
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.global.GlobalRouteSpecFactory

class CommandFacadeRouteSpec(override val componentContext: OpenAPIComponentContext) :
    RouteSpec,
    OpenAPIComponentContextCapable {
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
    override val parameters: List<Parameter> = buildList {
        add(componentContext.commandTypeHeaderParameter())
        add(componentContext.waitStageHeaderParameter())
        add(componentContext.waitContextHeaderParameter())
        add(componentContext.waitProcessorHeaderParameter())
        add(componentContext.waitTimeOutHeaderParameter())
        add(componentContext.tenantIdHeaderParameter())
        add(componentContext.ownerIdHeaderParameter())
        add(componentContext.aggregateIdHeaderParameter())
        add(componentContext.aggregateVersionHeaderParameter())
        add(componentContext.requestIdHeaderParameter())
        add(componentContext.localFirstHeaderParameter())
        add(componentContext.commandAggregateContextHeaderParameter())
        add(componentContext.commandAggregateNameHeaderParameter())
    }

    override val requestBody: RequestBody = RequestBodyBuilder()
        .description("command body")
        .content(
            schema = ObjectSchema()
        )
        .build()
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

class CommandFacadeRouteSpecFactory : GlobalRouteSpecFactory, AbstractRouteSpecFactory() {
    companion object {
        const val PATH = "/${Wow.WOW}/command/send"
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(CommandFacadeRouteSpec(componentContext))
    }
}
