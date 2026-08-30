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

package me.ahoo.wow.query.event

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.filter.ErrorHandler
import me.ahoo.wow.query.AbstractQueryGateway
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryLogErrorHandler
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryFilter
import me.ahoo.wow.query.mask.removeInternalEventBodyType
import me.ahoo.wow.query.mask.requiresInternalEventBodyType
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.node.ObjectNode

interface EventStreamQueryGateway : QueryGateway<DomainEventStream>

class DefaultEventStreamQueryGateway(
    namedAggregate: NamedAggregate,
    backend: EventStreamQueryBackend,
    filters: List<QueryFilter<QueryContext<*, *>>> = emptyList(),
    errorHandler: ErrorHandler<QueryContext<*, *>> = QueryLogErrorHandler(),
) : EventStreamQueryGateway,
    AbstractQueryGateway<DomainEventStream>(
        namedAggregate,
        backend,
        JsonSerializer.typeFactory.constructType(DomainEventStream::class.java),
        filters,
        EventStreamQueryGateway::class,
        errorHandler,
    ) {
    override fun prepareDynamicResult(query: Queryable<*>, result: ObjectNode): ObjectNode =
        if (query.projection.requiresInternalEventBodyType()) result.removeInternalEventBodyType() else result
}
