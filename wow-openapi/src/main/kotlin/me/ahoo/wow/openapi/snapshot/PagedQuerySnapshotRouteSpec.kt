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

package me.ahoo.wow.openapi.snapshot

import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef

class PagedQuerySnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
    override val appendTenantPath: Boolean
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("snapshot")
            .operation("paged_query")
            .build()
    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "snapshot/paged"

    override val summary: String
        get() = "Paged Query snapshot"
    override val requestBody: RequestBody = PagedQuery::class.java.toRequestBody()
    val responseSchemaRef = PagedList::class.java.toSchemaRef(
        propertyName = PagedList<*>::list.name,
        propertySchemaRef = MaterializedSnapshot::class.java.toSchemaRef(
            MaterializedSnapshot<*>::state.name,
            aggregateMetadata.state.aggregateType
        ),
        isArray = true
    )
    override val responses: ApiResponses
        get() = responseSchemaRef.ref.toResponse().let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }
}

class PagedQuerySnapshotRouteSpecFactory : AbstractAggregateRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<RouteSpec> {
        val defaultRouteSpec = PagedQuerySnapshotRouteSpec(currentContext, aggregateMetadata, false)
        defaultRouteSpec.responseSchemaRef.schemas.mergeSchemas()
        val appendTenantPath = aggregateMetadata.staticTenantId.isNullOrBlank()
        if (appendTenantPath) {
            val tenantRouteSpec = PagedQuerySnapshotRouteSpec(currentContext, aggregateMetadata, true)
            return listOf(defaultRouteSpec, tenantRouteSpec)
        }
        return listOf(defaultRouteSpec)
    }
}
