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

import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.models.media.IntegerSchema
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.asStringWithAlias
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.AggregateRouteSpec
import me.ahoo.wow.openapi.Https
import me.ahoo.wow.openapi.RoutePaths
import me.ahoo.wow.openapi.RouteSpec

class LoadEventStreamRouteSpec(
    override val currentContext: NamedBoundedContext,
    override val aggregateMetadata: AggregateMetadata<*, *>
) : AggregateRouteSpec() {

    override val id: String
        get() = "${aggregateMetadata.asStringWithAlias()}.loadEventStream"
    override val method: String
        get() = Https.Method.GET
    override val appendIdPath: Boolean
        get() = true
    override val summary: String
        get() = "Load Event Stream"
    override val isArrayResponse: Boolean
        get() = true
    override val responseType: Class<*>
        get() = DomainEventStream::class.java
    override val appendPathSuffix: String
        get() = "event/{${RoutePaths.COMPENSATE_HEAD_VERSION_KEY}}/{${RoutePaths.COMPENSATE_TAIL_VERSION_KEY}}"

    override fun build(): RouteSpec {
        super.build()
        addParameter(RoutePaths.COMPENSATE_HEAD_VERSION_KEY, ParameterIn.PATH, IntegerSchema()) {
            it.example(EventStore.DEFAULT_HEAD_VERSION)
        }
        addParameter(RoutePaths.COMPENSATE_TAIL_VERSION_KEY, ParameterIn.PATH, IntegerSchema()) {
            it.example(Int.MAX_VALUE)
        }
        return this
    }
}
