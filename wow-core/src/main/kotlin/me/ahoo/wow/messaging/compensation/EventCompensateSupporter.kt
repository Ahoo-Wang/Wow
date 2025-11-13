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

package me.ahoo.wow.messaging.compensation

import me.ahoo.wow.api.messaging.function.FunctionKind
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.compensation.DomainEventCompensator
import me.ahoo.wow.event.compensation.StateEventCompensator
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

/**
 * Supporter class that coordinates event compensation operations.
 *
 * This class acts as a facade for different types of event compensators,
 * routing compensation requests to the appropriate compensator based on
 * the function kind.
 *
 * @property domainEventCompensator Handles domain event compensations
 * @property stateEventCompensator Handles state event compensations
 */
class EventCompensateSupporter(
    private val domainEventCompensator: DomainEventCompensator,
    private val stateEventCompensator: StateEventCompensator
) {
    /**
     * Performs compensation for the specified aggregate and version.
     *
     * Routes the compensation request to the appropriate compensator based on
     * the function kind (EVENT or STATE_EVENT).
     *
     * @param aggregateId The ID of the aggregate to compensate
     * @param version The version to compensate from
     * @param target The compensation target specifying what to compensate
     * @return A Mono emitting the number of events resent, or an error for unsupported function kinds
     */
    fun compensate(
        aggregateId: AggregateId,
        version: Int,
        target: CompensationTarget
    ): Mono<Long> {
        return when (target.function.functionKind) {
            FunctionKind.EVENT -> domainEventCompensator.compensate(
                aggregateId = aggregateId,
                version = version,
                target = target
            )

            FunctionKind.STATE_EVENT -> stateEventCompensator.compensate(
                aggregateId = aggregateId,
                version = version,
                target = target
            )

            else -> {
                IllegalArgumentException("Unsupported FunctionKind:${target.function.functionKind}").toMono()
            }
        }
    }
}
