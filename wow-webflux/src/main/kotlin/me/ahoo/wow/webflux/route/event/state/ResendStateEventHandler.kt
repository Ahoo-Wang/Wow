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
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.EventStore.Companion.DEFAULT_HEAD_VERSION
import me.ahoo.wow.messaging.compensation.CompensationTarget
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.openapi.BatchResult
import reactor.core.publisher.Mono

class ResendStateEventHandler(
    private val aggregateMetadata: AggregateMetadata<*, *>,
    private val eventStore: EventStore,
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

    fun handle(cursorId: String, limit: Int): Mono<BatchResult> {
        val target = CompensationTarget(function = RESEND_FUNCTION)
        return eventStore.scanAggregateId(aggregateMetadata.namedAggregate, cursorId, limit)
            .flatMap { aggregateId ->
                stateEventCompensator.resend(
                    aggregateId = aggregateId,
                    target = target,
                    headVersion = DEFAULT_HEAD_VERSION,
                    tailVersion = Int.MAX_VALUE
                ).thenReturn(aggregateId)
            }
            .reduce(BatchResult(cursorId, 0)) { acc, aggregateId ->
                val nextCursorId = if (aggregateId.id > acc.cursorId) {
                    aggregateId.id
                } else {
                    acc.cursorId
                }
                BatchResult(nextCursorId, acc.size + 1)
            }
    }
}
