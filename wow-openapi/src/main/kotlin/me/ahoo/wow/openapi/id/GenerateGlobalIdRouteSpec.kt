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

package me.ahoo.wow.openapi.id

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.ComponentRef
import me.ahoo.wow.openapi.GlobalRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RouteSpec

object GenerateGlobalIdRouteSpec : RouteSpec {
    override val id: String = "wow.id.global"
    override val path: String = "/${Wow.WOW}/id/global"
    override val method: String = Https.Method.GET
    override val summary: String = "Generate Global ID"
    override val accept: List<String> = listOf(Https.MediaType.TEXT_PLAIN)
    override val parameters: List<Parameter> = emptyList()
    override val responses: ApiResponses = ApiResponses().addApiResponse(
        Https.Code.OK,
        StringSchema().toResponse(Https.MediaType.TEXT_PLAIN)
    )
}

class GenerateGlobalIdRouteSpecFactory : GlobalRouteSpecFactory {
    override val components: Components = ComponentRef.createComponents()

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(GenerateGlobalIdRouteSpec)
    }
}
