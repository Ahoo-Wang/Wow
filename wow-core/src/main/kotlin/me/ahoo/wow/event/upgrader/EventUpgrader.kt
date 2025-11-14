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

package me.ahoo.wow.event.upgrader

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.naming.Named
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.event.DomainEventRecord

/**
 * Interface for upgrading domain event records.
 *
 * Event upgraders are responsible for transforming domain event records from
 * older schema versions to newer ones. They are ordered and applied sequentially
 * to ensure proper event evolution.
 *
 * @see me.ahoo.wow.api.annotation.Order
 * @see EventNamedAggregate
 * @see DomainEventRecord
 */
interface EventUpgrader {
    /**
     * The event named aggregate this upgrader handles.
     */
    val eventNamedAggregate: EventNamedAggregate

    /**
     * Upgrades a domain event record to a newer schema version.
     *
     * @param domainEventRecord The event record to upgrade
     * @return The upgraded event record
     *
     * @see DomainEventRecord
     */
    fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord
}

interface EventNamedAggregate :
    NamedAggregate,
    Named {
    /**
     * The name of the event within the aggregate.
     */
    override val name: String

    companion object {
        /**
         * Extension function to convert a NamedAggregate to an EventNamedAggregate.
         *
         * @param eventName The name of the event
         * @receiver The named aggregate to convert
         * @return A new EventNamedAggregate with the specified event name
         *
         * @see NamedAggregate
         * @see MaterializedEventNamedAggregate
         * @see materialize
         */
        fun NamedAggregate.toEventNamedAggregate(eventName: String): EventNamedAggregate =
            MaterializedEventNamedAggregate(materialize(), eventName)
    }
}

/**
 * Materialized implementation of EventNamedAggregate.
 *
 * This data class provides a concrete implementation that holds both
 * the materialized named aggregate and the event name.
 *
 * @property namedAggregate The materialized named aggregate
 * @property name The name of the event
 *
 * @constructor Creates a new MaterializedEventNamedAggregate
 *
 * @param namedAggregate The materialized named aggregate
 * @param name The event name
 *
 * @see EventNamedAggregate
 * @see NamedAggregate
 * @see materialize
 */
data class MaterializedEventNamedAggregate(
    val namedAggregate: NamedAggregate,
    override val name: String
) : EventNamedAggregate,
    NamedAggregate by namedAggregate
