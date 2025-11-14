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
package me.ahoo.wow.event.metadata

import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.infra.accessor.property.PropertyGetter
import me.ahoo.wow.metadata.Metadata
import me.ahoo.wow.modeling.matedata.NamedAggregateGetter

/**
 * Metadata container for domain event classes.
 *
 * This data class holds metadata extracted from domain event classes, including
 * event names, revisions, aggregate information, and property getters for
 * accessing aggregate-related data.
 *
 * @param E The event type
 * @property eventType The class of the event
 * @property namedAggregateGetter Optional getter for extracting named aggregate information
 * @property name The name of the event
 * @property revision The revision/version of the event schema
 * @property aggregateIdGetter Optional getter for extracting aggregate ID from the event
 *
 * @constructor Creates a new EventMetadata instance
 *
 * @see Named
 * @see Metadata
 * @see NamedAggregateGetter
 * @see PropertyGetter
 */
data class EventMetadata<E>(
    val eventType: Class<E>,
    val namedAggregateGetter: NamedAggregateGetter<E>?,
    override val name: String,
    val revision: String,
    val aggregateIdGetter: PropertyGetter<E, String>? = null
) : Named,
    Metadata {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventMetadata<*>) return false

        if (eventType != other.eventType) return false

        return true
    }

    override fun hashCode(): Int = eventType.hashCode()

    override fun toString(): String = "EventMetadata(eventType=$eventType)"
}
