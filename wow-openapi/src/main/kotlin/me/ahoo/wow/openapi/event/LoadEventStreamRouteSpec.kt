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

import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponses
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.toStringWithAlias
import me.ahoo.wow.openapi.AbstractAggregateRouteSpecFactory
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.ResponseRef
import me.ahoo.wow.openapi.ResponseRef.Companion.toResponse
import me.ahoo.wow.openapi.ResponseRef.Companion.with
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.SchemaRef.Companion.toSchemaRef
import me.ahoo.wow.openapi.event.LoadEventStreamRouteSpecFactory.Companion.DOMAIN_EVENT_STREAM_ARRAY_RESPONSE

class LoadEventStreamRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec {

    override val id: String
        get() = "${aggregateMetadata.toStringWithAlias()}.loadEventStream"
    override val method: String
        get() = Https.Method.GET
    override val appendIdPath: Boolean
        get() = true
    override val summary: String
        get() = "Load Event Stream"
    override val responses: ApiResponses
        get() = ApiResponses().with(DOMAIN_EVENT_STREAM_ARRAY_RESPONSE)

    override val appendPathSuffix: String
        get() = "event/{${RoutePaths.HEAD_VERSION_KEY}}/{${RoutePaths.TAIL_VERSION_KEY}}"
    override val parameters: List<Parameter>
        get() = super.parameters + listOf(
            RoutePaths.HEAD_VERSION.ref,
            RoutePaths.TAIL_VERSION.ref
        )
}

class LoadEventStreamRouteSpecFactory : AbstractAggregateRouteSpecFactory() {
    init {
        DOMAIN_EVENT_STREAM_SCHEMA.schemas.mergeSchemas()
    }

    override fun create(
        currentContext: NamedBoundedContext,
        aggregateMetadata: AggregateMetadata<*, *>
    ): List<AggregateRouteSpec> {
        return listOf(LoadEventStreamRouteSpec(currentContext, aggregateMetadata))
    }

    companion object {
        val DOMAIN_EVENT_STREAM_SCHEMA = DomainEventStream::class.java.toSchemaRef()
        val DOMAIN_EVENT_STREAM_ARRAY_RESPONSE = DOMAIN_EVENT_STREAM_SCHEMA.ref.toResponse().let {
            ResponseRef("${Wow.WOW_PREFIX}EventStreamArray", it)
        }
    }
}
