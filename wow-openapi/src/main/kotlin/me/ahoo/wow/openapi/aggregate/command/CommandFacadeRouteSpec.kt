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
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.aggregateId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.aggregateVersion
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandAggregateContext
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandAggregateName
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.commandType
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.localFirst
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.ownerId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.requestId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.tenantId
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitContext
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitProcessor
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitStage
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Parameter.waitTimeOut
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.badRequest
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.illegalAccessDeletedAggregate
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.notFound
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.ok
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.requestTimeout
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.tooManyRequests
import me.ahoo.wow.openapi.aggregate.command.CommandComponent.Response.versionConflict
import me.ahoo.wow.openapi.aggregate.command.CommandFacadeRouteSpecFactory.Companion.PATH
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.global.GlobalRouteSpecFactory
import me.ahoo.wow.openapi.toJsonContent

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
    override val parameters: List<Parameter>
        get() {
            return buildList {
                add(componentContext.commandType())
                add(componentContext.waitStage())
                add(componentContext.waitContext())
                add(componentContext.waitProcessor())
                add(componentContext.waitTimeOut())
                add(componentContext.tenantId())
                add(componentContext.ownerId())
                add(componentContext.aggregateId())
                add(componentContext.aggregateVersion())
                add(componentContext.requestId())
                add(componentContext.localFirst())
                add(componentContext.commandAggregateContext())
                add(componentContext.commandAggregateName())
            }
        }
    override val requestBody: RequestBody = RequestBody()
        .required(true)
        .content(
            ObjectSchema().toJsonContent()
        )
    override val responses: ApiResponses
        get() = ApiResponses().apply {
            addApiResponse(Https.Code.OK, componentContext.ok())
            addApiResponse(Https.Code.BAD_REQUEST, componentContext.badRequest())
            addApiResponse(Https.Code.NOT_FOUND, componentContext.notFound())
            addApiResponse(Https.Code.CONFLICT, componentContext.versionConflict())
            addApiResponse(Https.Code.TOO_MANY_REQUESTS, componentContext.tooManyRequests())
            addApiResponse(Https.Code.REQUEST_TIMEOUT, componentContext.requestTimeout())
            addApiResponse(Https.Code.GONE, componentContext.illegalAccessDeletedAggregate())
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
