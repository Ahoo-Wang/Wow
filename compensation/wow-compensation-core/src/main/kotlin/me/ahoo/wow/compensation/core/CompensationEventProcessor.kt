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

package me.ahoo.wow.compensation.core

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.EventProcessor
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.configuration.MetadataSearcher.isLocal
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.messaging.compensation.EventCompensateSupporter
import reactor.core.publisher.Mono

@EventProcessor
class CompensationEventProcessor(
    private val eventCompensateSupporter: EventCompensateSupporter
) {
    companion object {
        private val log = KotlinLogging.logger { }
    }

    @Retry(enabled = false)
    @OnEvent
    fun onCompensationPrepared(compensationPrepared: CompensationPrepared, aggregateId: AggregateId): Mono<Void> {
        val isLocal = compensationPrepared.eventId.aggregateId.isLocal()
        if (!isLocal) {
            log.debug {
                "Skip compensationPrepared[Not Local Aggregate]:[$compensationPrepared] for aggregateId:[$aggregateId]."
            }
            return Mono.empty()
        }
        val eventAggregateId = compensationPrepared.eventId.aggregateId
        val eventVersion = compensationPrepared.eventId.version
        val target = CompensationTarget(
            id = aggregateId.id,
            function = compensationPrepared.function
        )
        return eventCompensateSupporter.compensate(
            aggregateId = eventAggregateId,
            version = eventVersion,
            target = target
        ).then()
    }
}
