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
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.exception.ErrorInfo
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.openapi.ComponentRef.Companion.createComponents
import me.ahoo.wow.openapi.GlobalRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas
import me.ahoo.wow.openapi.command.CommandWaitRouteSpecFactory.Companion.PATH

object CommandWaitRouteSpec : RouteSpec {
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
    override val requestBody: RequestBody = WaitSignal::class.java.toRequestBody()
    override val responses: ApiResponses = ApiResponses().addApiResponse(
        Https.Code.OK,
        ApiResponse().description(ErrorInfo.SUCCEEDED)
    )
}

class CommandWaitRouteSpecFactory : GlobalRouteSpecFactory {
    companion object {
        const val PATH = "/${Wow.WOW}/command/wait"
    }

    override val components: Components = createComponents()

    init {
        WaitSignal::class.java.toSchemas().mergeSchemas()
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(CommandWaitRouteSpec)
    }
}
