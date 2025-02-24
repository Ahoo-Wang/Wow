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
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.openapi.AbstractTenantOwnerAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.ResponseRef.Companion.withNotFound
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef
import me.ahoo.wow.openapi.route.AggregateRouteMetadata

class SingleSnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateRouteMetadata: AggregateRouteMetadata<*>,
    override val appendTenantPath: Boolean,
    override val appendOwnerPath: Boolean
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .appendOwner(appendOwnerPath)
            .resourceName("snapshot")
            .operation("single")
            .build()

    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "snapshot/single"

    override val summary: String
        get() = "Single snapshot"
    override val requestBody: RequestBody = SingleQuery::class.java.toRequestBody()

    private val responseSchema = MaterializedSnapshot::class.java.toSchemaRef(
        MaterializedSnapshot<*>::state.name,
        aggregateMetadata.state.aggregateType
    )

    override val responses: ApiResponses
        get() = responseSchema.ref.toResponse().let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }.withNotFound()
}

class SingleSnapshotRouteSpecFactory : AbstractTenantOwnerAggregateRouteSpecFactory() {
    override fun createSpec(
        currentContext: NamedBoundedContext,
        aggregateRouteMetadata: AggregateRouteMetadata<*>,
        appendTenantPath: Boolean,
        appendOwnerPath: Boolean
    ): AggregateRouteSpec {
        return SingleSnapshotRouteSpec(
            currentContext = currentContext,
            aggregateRouteMetadata = aggregateRouteMetadata,
            appendTenantPath = appendTenantPath,
            appendOwnerPath = appendOwnerPath
        )
    }
}
