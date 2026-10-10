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

package me.ahoo.wow.openapi.catalog

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.openapi.metadata.AggregateRouteMetadata

/**
 * Describes routes. A contributor only builds [HttpRouteContract]s, which are pure data: it generates no schema and
 * registers no component (it references them, see [me.ahoo.wow.openapi.contract.HttpComponent] and
 * [me.ahoo.wow.openapi.contract.HttpSchema.TypeRef]).
 *
 * [me.ahoo.wow.openapi.RouterSpecs] calls [contributeGlobal] on every contributor, then [contributeAggregate] on every
 * contributor for each aggregate whose routes are enabled. The order routes are contributed in does not matter: the
 * route catalog orders them itself.
 */
interface RouteContributor {
    fun contributeGlobal(currentContext: NamedBoundedContext): List<HttpRouteContract> = emptyList()

    fun contributeAggregate(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<HttpRouteContract> = emptyList()
}
