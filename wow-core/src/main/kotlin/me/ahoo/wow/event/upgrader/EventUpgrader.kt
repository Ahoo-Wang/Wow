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
 * @see me.ahoo.wow.api.annotation.Order
 */
interface EventUpgrader {
    val eventNamedAggregate: EventNamedAggregate
    fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord
}

interface EventNamedAggregate : NamedAggregate, Named {
    /**
     * event mame
     */
    override val name: String

    companion object {
        fun NamedAggregate.asEventNamedAggregate(eventName: String): EventNamedAggregate {
            return MaterializedEventNamedAggregate(materialize(), eventName)
        }
    }
}

data class MaterializedEventNamedAggregate(
    val namedAggregate: NamedAggregate,
    override val name: String
) : EventNamedAggregate, NamedAggregate by namedAggregate
