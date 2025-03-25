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

package me.ahoo.wow.openapi.global

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.openapi.AbstractRouteSpecFactory
import me.ahoo.wow.openapi.ApiResponseBuilder
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyBuilder
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.global.CommandWaitRouteSpecFactory.Companion.PATH

class CommandWaitRouteSpec(override val componentContext: OpenAPIComponentContext) : RouteSpec,
    OpenAPIComponentContextCapable {
    override val id: String = RouteIdSpec()
        .prefix(Wow.WOW)
        .resourceName("command")
        .operation("wait")
        .build()
    override val path: String
        get() = PATH
    override val method: String
        get() = Https.Method.POST
    override val summary: String = "command wait handler"
    override val parameters: List<Parameter> = listOf()
    override val requestBody: RequestBody =
        RequestBodyBuilder().content(schema = componentContext.schema(WaitSignal::class.java))
            .build()
    override val responses: ApiResponses = ApiResponses().addApiResponse(
        Https.Code.OK,
        ApiResponseBuilder().description(ErrorInfo.SUCCEEDED).build()
    )
}

class CommandWaitRouteSpecFactory : GlobalRouteSpecFactory, AbstractRouteSpecFactory() {
    companion object {
        const val PATH = "/${Wow.WOW}/command/wait"
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(CommandWaitRouteSpec(componentContext))
    }
}
