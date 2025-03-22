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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.event.upgrader.EventNamedAggregate.Companion.toEventNamedAggregate
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.toMutableDomainEventRecord
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.event.DomainEventRecord
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object EventUpgraderFactory {
    private val log = KotlinLogging.logger {}
    private val eventUpgraderFactories: ConcurrentHashMap<EventNamedAggregate, List<EventUpgrader>> =
        ConcurrentHashMap()

    init {
        ServiceLoader.load(EventUpgrader::class.java)
            .forEach {
                log.info { "Load $it to register." }
                register(it)
            }
    }

    fun register(eventUpgrader: EventUpgrader) {
        log.info {
            "Register $eventUpgrader."
        }
        eventUpgraderFactories.compute(eventUpgrader.eventNamedAggregate) { _, value ->
            if (value == null) {
                mutableListOf(eventUpgrader)
            } else {
                (value + eventUpgrader).sortedByOrder()
            }
        }
    }

    fun get(eventNamedAggregate: EventNamedAggregate): List<EventUpgrader> {
        return eventUpgraderFactories[eventNamedAggregate] ?: listOf()
    }

    fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        val namedAggregate = domainEventRecord.toAggregateId().namedAggregate.materialize()
        val eventNamedAggregate = namedAggregate.toEventNamedAggregate(domainEventRecord.name)
        val eventUpgraders = get(eventNamedAggregate)
        if (eventUpgraders.isEmpty()) {
            return domainEventRecord
        }
        var mutableDomainEventRecord = domainEventRecord.toMutableDomainEventRecord()
        eventUpgraders.forEach {
            log.debug {
                "Upgrade [${domainEventRecord.id}]@[$eventNamedAggregate] by $it."
            }
            mutableDomainEventRecord = it.upgrade(mutableDomainEventRecord).toMutableDomainEventRecord()
        }
        return domainEventRecord
    }
}
