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

package me.ahoo.wow.openapi.contributor

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.catalog.RouteCategory
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

object LegacyRouteContributor : RouteContributor {
    override val id: String = "legacy-route-spec-adapter"
    override val category: RouteCategory = RouteCategory.GLOBAL
    override val order: Int = Int.MAX_VALUE

    override fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return emptyList()
    }

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext
    ): List<HttpRouteContract> {
        return emptyList()
    }
}
