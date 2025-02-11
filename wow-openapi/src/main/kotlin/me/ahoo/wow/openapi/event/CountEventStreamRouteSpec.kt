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

package me.ahoo.wow.openapi.event

import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.route.AggregateRouteMetadata

class CountEventStreamRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val appendTenantPath: Boolean
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("event")
            .operation("count")
            .build()

    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "event/count"

    override val summary: String
        get() = "Count Event Stream"
    override val requestBody: RequestBody = Condition::class.java.toRequestBody()

    override val responses: ApiResponses
        get() = IntegerSchema().toResponse().let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }
}

class CountEventStreamRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>
    ): List<RouteSpec> {
        val defaultRouteSpec = CountEventStreamRouteSpec(currentContext, aggregateRouteMetadata, false)
        val appendTenantPath = aggregateRouteMetadata.aggregateMetadata.staticTenantId.isNullOrBlank()
        if (appendTenantPath) {
            val tenantRouteSpec = CountEventStreamRouteSpec(currentContext, aggregateRouteMetadata, true)
            return listOf(defaultRouteSpec, tenantRouteSpec)
        }
        return listOf(defaultRouteSpec)
    }
}
