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
import me.ahoo.wow.openapi.contract.BuiltInHttpRouteHandlerKeys
import me.ahoo.wow.openapi.contract.BuiltInHttpRoutePaths
import me.ahoo.wow.openapi.contract.HttpContent
import me.ahoo.wow.openapi.contract.HttpRequestBody
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.contract.HttpSchema
import me.ahoo.wow.openapi.contributor.commandFacadeParameterRefs
import me.ahoo.wow.openapi.contributor.commandResponseRefs

object CommandFacadeRouteContributor : RouteContributor {
    override val id: String = "global.command-facade"
    override val category: RouteCategory = RouteCategory.GLOBAL
    override val order: Int = 20

    override fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return listOf(
            HttpRouteContract(
                routeId = wowRouteId("command", "send"),
                method = Https.Method.POST,
                path = BuiltInHttpRoutePaths.Global.COMMAND_SEND,
                handlerKey = BuiltInHttpRouteHandlerKeys.Global.COMMAND_FACADE,
                summary = "Unified Sending Endpoint For Command Messages",
                parameters = componentContext.commandFacadeParameterRefs(),
                requestBody = HttpRequestBody(
                    description = "Command Message Body",
                    content = listOf(HttpContent(Https.MediaType.APPLICATION_JSON, HttpSchema.Object))
                ),
                responses = componentContext.commandResponseRefs(),
                tags = wowTags()
            )
        )
    }
}
