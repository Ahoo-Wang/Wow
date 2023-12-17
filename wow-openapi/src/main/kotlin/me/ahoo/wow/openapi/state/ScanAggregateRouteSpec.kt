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

package me.ahoo.wow.openapi.state

import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.BatchRouteSpec
import me.ahoo.wow.openapi.BatchRouteSpecFactory
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas

class ScanAggregateRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : BatchRouteSpec {
    override val id: String
        get() = "${aggregateMetadata.toStringWithAlias()}.scanAggregate"
    override val method: String
        get() = Https.Method.GET

    override val appendPathSuffix: String
        get() = "state/{${RoutePaths.BATCH_CURSOR_ID}}/{${RoutePaths.BATCH_LIMIT}}"

    override val summary: String
        get() = "Scan state aggregate"
    override val responses: ApiResponses
        get() = aggregateMetadata.state.aggregateType.toResponse(true).let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }
}

class ScanAggregateRouteSpecFactory : BatchRouteSpecFactory() {

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<ScanAggregateRouteSpec> {
        aggregateMetadata.state.aggregateType.toSchemas().mergeSchemas()
        return listOf(ScanAggregateRouteSpec(currentContext, aggregateMetadata))
    }
}
