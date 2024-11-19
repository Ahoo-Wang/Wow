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

package me.ahoo.wow.webflux.route.event.state

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.messaging.function.FunctionInfoData
import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.event.compensation.StateEventCompensator
import me.ahoo.wow.eventsourcing.EventStore.Companion.DEFAULT_HEAD_VERSION
import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.BatchResult
import me.ahoo.wow.webflux.exception.onErrorMapBatchTaskException
import me.ahoo.wow.webflux.route.toBatchResult
import reactor.core.publisher.Mono

class ResendStateEventHandler(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val snapshotRepository: SnapshotRepository,
    private val stateEventCompensator: StateEventCompensator
) {
    companion object {
        private val RESEND_FUNCTION =
            FunctionInfoData(
                functionKind = FunctionKind.STATE_EVENT,
                contextName = Wow.WOW,
                processorName = "ResendStateEventHandler",
                name = "Resend"
            )
    }

    fun handle(afterId: String, limit: Int): Mono<BatchResult> {
        val target = CompensationTarget(function = RESEND_FUNCTION)
        return snapshotRepository.scanAggregateId(aggregateMetadata.namedAggregate, afterId, limit)
            .flatMapSequential { aggregateId ->
                stateEventCompensator.resend(
                    aggregateId = aggregateId,
                    target = target,
                    headVersion = DEFAULT_HEAD_VERSION,
                    tailVersion = Int.MAX_VALUE
                ).thenReturn(aggregateId).onErrorMapBatchTaskException(aggregateId)
            }.toBatchResult(afterId)
    }
}
