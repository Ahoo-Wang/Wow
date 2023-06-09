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

import me.ahoo.wow.annotation.OrderComparator
import me.ahoo.wow.event.upgrader.EventNamedAggregate.Companion.asEventNamedAggregate
import me.ahoo.wow.event.upgrader.MutableDomainEventRecord.Companion.asMutableDomainEventRecord
import me.ahoo.wow.modeling.materialize
import me.ahoo.wow.serialization.event.DomainEventRecord
import org.slf4j.LoggerFactory
import java.util.ServiceLoader
import java.util.SortedSet
import java.util.concurrent.ConcurrentHashMap

object EventUpgraderFactory {
    private val log = LoggerFactory.getLogger(EventUpgraderFactory::class.java)
    private val eventUpgraderFactories: ConcurrentHashMap<EventNamedAggregate, SortedSet<EventUpgrader>> =
        ConcurrentHashMap()

    init {
        ServiceLoader.load(EventUpgrader::class.java)
            .forEach {
                if (log.isInfoEnabled) {
                    log.info("Load $it to register.")
                }
                register(it)
            }
    }

    fun register(eventUpgrader: EventUpgrader) {
        if (log.isInfoEnabled) {
            log.info("Register $eventUpgrader.")
        }
        eventUpgraderFactories.compute(eventUpgrader.eventNamedAggregate) { _, value ->
            if (value == null) {
                sortedSetOf(OrderComparator, eventUpgrader)
            } else {
                value.add(eventUpgrader)
                value
            }
        }
    }

    fun get(eventNamedAggregate: EventNamedAggregate): Set<EventUpgrader> {
        return eventUpgraderFactories[eventNamedAggregate] ?: setOf()
    }

    fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord {
        val namedAggregate = domainEventRecord.asAggregateId().namedAggregate.materialize()
        val eventNamedAggregate = namedAggregate.asEventNamedAggregate(domainEventRecord.name)
        val eventUpgraders = get(eventNamedAggregate)
        if (eventUpgraders.isEmpty()) {
            return domainEventRecord
        }
        var mutableDomainEventRecord = domainEventRecord.asMutableDomainEventRecord()
        eventUpgraders.forEach {
            if (log.isDebugEnabled) {
                log.debug("Upgrade [${domainEventRecord.id}]@[$eventNamedAggregate] by $it.")
            }
            mutableDomainEventRecord = it.upgrade(mutableDomainEventRecord).asMutableDomainEventRecord()
        }
        return domainEventRecord
    }
}
