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

package me.ahoo.wow.openapi.contributor.global

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contributor.badRequestResponseRef
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpResponse
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest

object GenerateBIScriptRouteContributor : RouteContributor {
    override val id: String = "global.bi-script"
    override val category: RouteCategory = RouteCategory.GLOBAL
    override val order: Int = 50

    override fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return listOf(
            HttpRouteContract(
                routeId = wowRouteId("bi_script", "generate"),
                method = Https.Method.POST,
                path = BuiltInHttpRoutePaths.Global.BI_SCRIPT,
                handlerKey = BuiltInHttpRouteHandlerKeys.Global.BI_SCRIPT,
                summary = "Generate BI Sync Script",
                accept = listOf(Https.MediaType.APPLICATION_SQL),
                requestBody = HttpRequestBody(
                    required = true,
                    description = "BI script option overrides.",
                    content = listOf(
                        HttpContent(
                            Https.MediaType.APPLICATION_JSON,
                            HttpSchema.TypeRef(BiScriptRequest::class.java)
                        )
                    )
                ),
                responses = listOf(
                    HttpResponse(
                        statusCode = Https.Code.OK,
                        description = "The generated BI synchronization script.",
                        content = listOf(HttpContent(Https.MediaType.APPLICATION_SQL, HttpSchema.String))
                    ),
                    componentContext.badRequestResponseRef()
                ),
                tags = wowTags()
            )
        )
    }
}
