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

package me.ahoo.wow.openapi.bi

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.bi.MessageHeaderSqlType
import me.ahoo.wow.openapi.ComponentRef
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.bi.GenerateBIScriptRouteSpecFactory.Companion.BI_HEADER_TYPE_HEADER
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.context.OpenAPIComponentContextCapable
import me.ahoo.wow.openapi.global.GlobalRouteSpecFactory

class GenerateBIScriptRouteSpec(override val componentContext: OpenAPIComponentContext) : RouteSpec,
    OpenAPIComponentContextCapable {
    override val id: String = RouteIdSpec()
        .prefix(Wow.WOW)
        .resourceName("bi_script")
        .operation("generate")
        .build()

    override val path: String = "/${Wow.WOW}/bi/script"
    override val method: String = Https.Method.GET
    override val summary: String = "Generate BI Sync Script"
    override val parameters: List<Parameter> = listOf(componentContext.parameter {
        name = BI_HEADER_TYPE_HEADER
        `in` = ParameterIn.HEADER.toString()
        schema = componentContext.schema(MessageHeaderSqlType::class.java)
        description = "The type of BI Message header."
    })
    override val accept: List<String> = listOf(Https.MediaType.APPLICATION_SQL)
    override val responses: ApiResponses = ApiResponses().addApiResponse(
        Https.Code.OK,
        StringSchema().toResponse(mediaType = Https.MediaType.APPLICATION_SQL)
    )
}

class GenerateBIScriptRouteSpecFactory : GlobalRouteSpecFactory, OpenAPIComponentContextCapable {
    companion object {
        const val BI_HEADER_TYPE_HEADER = "Wow-BI-Header-Sql-Type"
    }

    override lateinit var componentContext: OpenAPIComponentContext

    override fun initialize(componentContext: OpenAPIComponentContext) {
        this.componentContext = componentContext
    }

    override fun create(currentContext: NamedBoundedContext): List<RouteSpec> {
        return listOf(GenerateBIScriptRouteSpec(componentContext))
    }
}
