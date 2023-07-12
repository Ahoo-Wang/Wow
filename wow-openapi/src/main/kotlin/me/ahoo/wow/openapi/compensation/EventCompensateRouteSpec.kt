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

package me.ahoo.wow.openapi.compensation

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.messaging.compensation.CompensationConfig
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RoutePaths.COMPENSATE_HEAD_VERSION_KEY
import me.ahoo.wow.openapi.RoutePaths.COMPENSATE_TAIL_VERSION_KEY
import me.ahoo.wow.openapi.RouteSpec

abstract class EventCompensateRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>,
) : AggregateRouteSpec() {

    abstract val topicKind: String
    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.${topicKind}Compensate"
    override val summary: String
        get() = "$topicKind compensate"
    override val method: String
        get() = Https.Method.PUT
    override val appendPathSuffix: String
        get() = "$topicKind/{$COMPENSATE_HEAD_VERSION_KEY}/{$COMPENSATE_TAIL_VERSION_KEY}/compensate"
    override val requestBodyType: Class<*>
        get() = CompensationConfig::class.java
    override val responseType: Class<*>?
        get() = Long::class.java
    override val appendIdPath: Boolean
        get() = true

    override fun customize(apiResponse: ApiResponse): ApiResponse {
        return apiResponse.description("Number of event streams compensated")
    }

    override fun build(): RouteSpec {
        super.build()
        addParameter(COMPENSATE_HEAD_VERSION_KEY, ParameterIn.PATH, IntegerSchema()) {
            it.example(EventStore.DEFAULT_HEAD_VERSION)
        }
        addParameter(COMPENSATE_TAIL_VERSION_KEY, ParameterIn.PATH, IntegerSchema()) {
            it.example(Int.MAX_VALUE)
        }
        return this
    }
}
