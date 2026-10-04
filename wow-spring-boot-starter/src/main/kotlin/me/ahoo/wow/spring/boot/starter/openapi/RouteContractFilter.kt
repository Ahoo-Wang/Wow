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

package me.ahoo.wow.spring.boot.starter.openapi

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.context.OpenAPIComponentContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

/**
 * [delegate] without the routes [keep] rejects, for a route whose handler this application does not provide.
 */
internal class RouteContractFilter(
    private val delegate: RouteContributor,
    private val keep: (HttpRouteContract) -> Boolean,
) : RouteContributor by delegate {
    override fun contributeGlobal(
        currentContext: NamedBoundedContext,
        componentContext: OpenAPIComponentContext,
    ): List<HttpRouteContract> = delegate.contributeGlobal(currentContext, componentContext).filter(keep)

    override fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        componentContext: OpenAPIComponentContext,
    ): List<HttpRouteContract> =
        delegate.contributeAggregate(currentContext, aggregateRouteMetadata, componentContext).filter(keep)
}
