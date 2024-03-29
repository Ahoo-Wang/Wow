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
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.toRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef

class PagedQuerySnapshotRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec {
    override val id: String
        get() = "${aggregateMetadata.toStringWithAlias()}.pagedQuerySnapshot"
    override val method: String
        get() = Https.Method.POST

    override val appendPathSuffix: String
        get() = "snapshot/pagination"

    override val summary: String
        get() = "Paged Query snapshot"
    override val requestBody: RequestBody = PagedQuery::class.java.toRequestBody()
    val responseSchemaRef = PagedList::class.java.toSchemaRef(
        PagedList<*>::list.name,
        Snapshot::class.java.toSchemaRef(
            Snapshot<*>::state.name,
            aggregateMetadata.state.aggregateType
        )
    )
    override val responses: ApiResponses
        get() = responseSchemaRef.ref.toResponse().let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }
}

class PagedQuerySnapshotRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    init {
        PagedQuery::class.java.toSchemaRef().schemas.mergeSchemas()
    }

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<RouteSpec> {
        val routeSpec = PagedQuerySnapshotRouteSpec(currentContext, aggregateMetadata)
        routeSpec.responseSchemaRef.schemas.mergeSchemas()
        return listOf(routeSpec)
    }
}
