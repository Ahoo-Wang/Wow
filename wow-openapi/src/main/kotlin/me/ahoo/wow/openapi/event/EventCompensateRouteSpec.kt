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
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RequestBodyRef.Companion.asRequestBody
import me.ahoo.wow.openapi.ResponseRef.Companion.asResponse
import me.ahoo.wow.openapi.ResponseRef.Companion.withBadRequest
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.SchemaRef.Companion.asSchemaRef
import me.ahoo.wow.openapi.event.EventCompensateRouteSpecFactory.Companion.COMPENSATION_TARGET_REQUEST
import me.ahoo.wow.serialization.MessageRecords

abstract class EventCompensateRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
) : AggregateRouteSpec {

    abstract val topicKind: String
    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.${topicKind}Compensate"
    override val summary: String
        get() = "$topicKind compensate"
    override val method: String
        get() = Https.Method.PUT
    override val appendPathSuffix: String
        get() = "$topicKind/{${MessageRecords.VERSION}}/compensate"
    override val requestBody: RequestBody? = COMPENSATION_TARGET_REQUEST
    override val responses: ApiResponses
        get() = IntegerSchema().asResponse().let {
            it.description("Number of event streams compensated")
            ApiResponses().addApiResponse(Https.Code.OK, it)
        }.withBadRequest()
    override val appendIdPath: Boolean
        get() = true
    override val parameters: List<Parameter>
        get() = super.parameters + RoutePaths.VERSION
}

abstract class EventCompensateRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    companion object {
        val COMPENSATION_TARGET_SCHEMA = CompensationTarget::class.java.asSchemaRef()
        val COMPENSATION_TARGET_REQUEST = CompensationTarget::class.java.asRequestBody()
    }
}
