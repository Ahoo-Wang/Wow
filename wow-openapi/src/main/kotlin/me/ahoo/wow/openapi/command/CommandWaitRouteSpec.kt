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

class CommandWaitRouteSpec(
    override val id: String,
    override val path: String,
    override val method: String,
    override val summary: String,
    override val description: String,
    override val requestBody: RequestBody?,
    override val responses: ApiResponses
) : RouteSpec {
    override val parameters: List<Parameter> = listOf()
}

class CommandWaitRouteSpecFactory : GlobalRouteSpecFactory {
    companion object {
        private val ID = RouteIdSpec()
            .prefix(Wow.WOW)
            .resourceName("command")
            .operation("wait")
            .build()

        const val PATH = "/${Wow.WOW}/command/wait"
        const val METHOD = Https.Method.POST
        const val SUMMARY = "command wait handler"
        const val DESCRIPTION = ""
        val CommandWaitRouteSpec = CommandWaitRouteSpec(
            ID,
            PATH,
            METHOD,
            SUMMARY,
            DESCRIPTION,
            WaitSignal::class.java.toRequestBody(),
            ApiResponses().addApiResponse(
                Https.Code.OK,
                ApiResponse().description(ErrorInfo.SUCCEEDED)
            )
        )
    }

    override val components: Components = createComponents()

    init {
        WaitSignal::class.java.toSchemas().mergeSchemas()
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(CommandWaitRouteSpec)
    }
}
