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

package me.ahoo.wow.openapi.converter

import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.serialization.MessageRecords

/**
 * StateEvent Converter
 * @see me.ahoo.wow.serialization.event.DomainEventJsonSerializer
 * @see me.ahoo.wow.serialization.event.EventStreamJsonSerializer
 * @see me.ahoo.wow.serialization.event.StateEventJsonSerializer
 */
class StateEventConverter : TargetTypeModifyConverter() {
    override val targetType: Class<*> = StateEvent::class.java

    override fun modify(resolvedSchema: Schema<*>): Schema<*> {
        resolvedSchema.properties.remove(StateEvent<*>::operator.name)
        resolvedSchema.properties.remove(StateEvent<*>::eventTime.name)
        resolvedSchema.properties.remove(StateEvent<*>::eventId.name)
        resolvedSchema.properties.remove(StateEvent<*>::size.name)
        resolvedSchema.properties.remove(StateEvent<*>::aggregateId.name)
        resolvedSchema.properties.remove("readOnly")
        resolvedSchema.properties.remove("initialVersion")
        resolvedSchema.properties.remove("initialized")
        resolvedSchema.properties.remove(StateEvent<*>::expectedNextVersion.name)
        resolvedSchema.properties[MessageRecords.AGGREGATE_ID] = StringSchema()
        resolvedSchema.properties[MessageRecords.TENANT_ID] = StringSchema()
        return resolvedSchema
    }
}
