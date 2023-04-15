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

package me.ahoo.wow.event

import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.EventStore
import reactor.core.publisher.Mono

const val COMPENSATE_TARGET_PROCESSOR_KEY = "compensate"
const val COMPENSATE_TARGET_PROCESSOR_SEPARATOR = ","

/**
 * 事件补偿器
 */
interface EventCompensator {
    fun compensate(
        aggregateId: AggregateId,
        targetProcessors: Set<String> = setOf(),
        headVersion: Int = EventStore.DEFAULT_HEAD_VERSION,
        tailVersion: Int = Int.MAX_VALUE,
    ): Mono<Long>
}

class DefaultEventCompensator(
    private val eventStore: EventStore,
    private val eventBus: DomainEventBus,
) :
    EventCompensator {
    override fun compensate(
        aggregateId: AggregateId,
        targetProcessors: Set<String>,
        headVersion: Int,
        tailVersion: Int,
    ): Mono<Long> {
        return eventStore.load(
            aggregateId = aggregateId,
            headVersion = headVersion,
            tailVersion = tailVersion,
        ).concatMap {
            val eventStream = it.mergeHeader(
                mapOf(
                    COMPENSATE_TARGET_PROCESSOR_KEY to targetProcessors.joinToString(
                        COMPENSATE_TARGET_PROCESSOR_SEPARATOR,
                    ),
                ),
            )
            eventBus.send(eventStream).thenReturn(it)
        }.count()
    }
}

val <T> Message<T>.compensateTargetProcessors: Set<String>?
    get() {
        val targetProcessorsString = header[COMPENSATE_TARGET_PROCESSOR_KEY] ?: return null
        if (targetProcessorsString.isBlank()) {
            return emptySet()
        }
        return targetProcessorsString
            .split(COMPENSATE_TARGET_PROCESSOR_SEPARATOR)
            .filter { it.isNotBlank() }
            .toSet()
    }

fun <T> Message<T>.shouldHandle(processorName: String, defaultIfNull: Boolean = true): Boolean {
    val targetProcessors = this.compensateTargetProcessors ?: return defaultIfNull
    return targetProcessors.isEmpty() || targetProcessors.contains(processorName)
}
