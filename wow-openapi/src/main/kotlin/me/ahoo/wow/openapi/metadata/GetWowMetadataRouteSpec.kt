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

package me.ahoo.wow.openapi.metadata

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.WowMetadata
import me.ahoo.wow.openapi.ComponentRef
import me.ahoo.wow.openapi.GlobalRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas

object GetWowMetadataRouteSpec : RouteSpec {
    override val id: String = "wow.metadata.get"
    override val path: String = "/${Wow.WOW}/metadata"
    override val method: String = Https.Method.GET
    override val summary: String = "Get Wow Metadata"
    override val parameters: List<Parameter> = emptyList()
    override val responses: ApiResponses = ApiResponses().addApiResponse(
        Https.Code.OK,
        WowMetadata::class.java.toResponse()
    )
}

class GetWowMetadataRouteSpecFactory : GlobalRouteSpecFactory {
    override val components: Components = ComponentRef.createComponents()
    init {
        WowMetadata::class.java.toSchemas().mergeSchemas()
    }
    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(GetWowMetadataRouteSpec)
    }
}
