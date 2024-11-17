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

package me.ahoo.wow.openapi.event.state

import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.BatchRouteSpec
import me.ahoo.wow.openapi.BatchRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RoutePaths

class ResendStateEventRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : BatchRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("state_event")
            .operation("resend")
            .build()
    override val method: String
        get() = Https.Method.POST
    override val summary: String
        get() = "Resend State Event"
    override val appendPathSuffix: String
        get() = "state/{${RoutePaths.BATCH_AFTER_ID}}/{${RoutePaths.BATCH_LIMIT}}"
}

class ResendStateEventRouteSpecFactory : BatchRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<ResendStateEventRouteSpec> {
        return listOf(ResendStateEventRouteSpec(currentContext, aggregateMetadata))
    }
}
