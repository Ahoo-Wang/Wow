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

import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpecFactory.Companion.DOMAIN_EVENT_STREAM_ARRAY_RESPONSE

class ListQueryEventStreamRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
    override val appendTenantPath: Boolean
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("event")
            .operation("list_query")
            .build()
    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "event/list"

    override val summary: String
        get() = "List Query Event Stream"
    override val requestBody: RequestBody = ListQuery::class.java.toRequestBody()

    override val responses: ApiResponses
        get() = ApiResponses().with(DOMAIN_EVENT_STREAM_ARRAY_RESPONSE)
}

class ListQueryEventStreamRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<RouteSpec> {
        val defaultRouteSpec = ListQueryEventStreamRouteSpec(currentContext, aggregateMetadata, false)
        val appendTenantPath = aggregateMetadata.staticTenantId.isNullOrBlank()
        if (appendTenantPath) {
            val tenantRouteSpec = ListQueryEventStreamRouteSpec(currentContext, aggregateMetadata, true)
            return listOf(defaultRouteSpec, tenantRouteSpec)
        }
        return listOf(defaultRouteSpec)
    }
}
