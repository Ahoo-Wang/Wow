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

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ParameterRef
import me.ahoo.wow.openapi.ParameterRef.Companion.with
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.ResponseRef.Companion.withBadRequest
import me.ahoo.wow.openapi.ResponseRef.Companion.withNotFound
import me.ahoo.wow.openapi.RouteIdSpec
import me.ahoo.wow.openapi.RouteSpec
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemas
import me.ahoo.wow.openapi.state.LoadVersionedAggregateRouteSpecFactory.Companion.VERSION_PARAMETER
import me.ahoo.wow.serialization.MessageRecords

class LoadTimeBasedAggregateRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec {
    override val id: String
        get() = RouteIdSpec()
            .aggregate(aggregateMetadata)
            .appendTenant(appendTenantPath)
            .resourceName("time_based_aggregate")
            .operation("load")
            .build()

    override val method: String
        get() = Https.Method.GET
    override val appendIdPath: Boolean
        get() = true

    override val appendPathSuffix: String
        get() = "state/{${MessageRecords.CREATE_TIME}}"

    override val summary: String
        get() = "Load time based state aggregate"

    override val parameters: List<Parameter>
        get() = super.parameters + VERSION_PARAMETER.ref
    override val requestBody: RequestBody? = null
    override val responses: ApiResponses
        get() = aggregateMetadata.state.aggregateType.toResponse().let {
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }.withBadRequest().withNotFound()
}

class LoadTimeBasedAggregateRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    companion object {
        val VERSION_PARAMETER = Parameter()
            .name(MessageRecords.VERSION)
            .`in`(ParameterIn.PATH.toString())
            .schema(IntegerSchema())
            .example(Int.MAX_VALUE).let {
                ParameterRef("${Wow.WOW_PREFIX}${MessageRecords.VERSION}", it)
            }
    }

    init {
        components.parameters
            .with(VERSION_PARAMETER)
    }

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<RouteSpec> {
        aggregateMetadata.state.aggregateType.toSchemas().mergeSchemas()
        return listOf(LoadTimeBasedAggregateRouteSpec(currentContext, aggregateMetadata))
    }
}
