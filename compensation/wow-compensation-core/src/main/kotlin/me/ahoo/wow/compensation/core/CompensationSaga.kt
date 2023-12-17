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

import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.StatelessSaga
import me.ahoo.wow.api.messaging.FunctionKind
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.compensation.api.CompensationPrepared
import me.ahoo.wow.configuration.MetadataSearcher.isLocal
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.messaging.compensation.CompensationTarget
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@StatelessSaga
class CompensationSaga(
    private val domainEventCompensator: DomainEventCompensator,
    private val stateEventCompensator: StateEventCompensator
) {
    companion object {
        private val log = LoggerFactory.getLogger(CompensationSaga::class.java)
    }

    @OnEvent
    fun onCompensationPrepared(compensationPrepared: CompensationPrepared, aggregateId: AggregateId): Mono<Void> {
        val isLocal = compensationPrepared.eventId.aggregateId.isLocal()
        if (!isLocal) {
            if (log.isDebugEnabled) {
                log.debug("Skip compensationPrepared:[{}] for aggregateId:[{}].", compensationPrepared, aggregateId)
            }
            return Mono.empty()
        }
        val eventAggregateId = compensationPrepared.eventId.aggregateId
        val eventVersion = compensationPrepared.eventId.version
        val target = CompensationTarget(id = aggregateId.id, processor = compensationPrepared.processor)
        return when (compensationPrepared.functionKind) {
            FunctionKind.EVENT -> domainEventCompensator.compensate(eventAggregateId, eventVersion, target).then()
            FunctionKind.STATE_EVENT -> stateEventCompensator.compensate(eventAggregateId, eventVersion, target).then()
            else -> {
                Mono.empty()
            }
        }
    }
}
