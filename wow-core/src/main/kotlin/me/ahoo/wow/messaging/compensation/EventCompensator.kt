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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.event.DomainEventStream
import reactor.core.publisher.Mono

/**
 * Interface for event compensators that can resend events for compensation purposes.
 *
 * Event compensators handle the logic of replaying events to compensate for
 * failed or incorrect processing of previous events.
 *
 * @param E The type of domain event stream this compensator handles
 */
interface EventCompensator<E : DomainEventStream> {
    /**
     * Compensates by processing the given event stream.
     *
     * @param eventStream The event stream to compensate with
     * @param target The compensation target
     * @return A Mono that completes when compensation is done
     */
    fun compensate(
        eventStream: E,
        target: CompensationTarget
    ): Mono<Void>

    /**
     * Compensates events for the specified aggregate and version.
     *
     * Default implementation delegates to resend with the same head and tail version.
     *
     * @param aggregateId The aggregate ID to compensate
     * @param version The version to compensate from
     * @param target The compensation target
     * @return A Mono emitting the number of events resent
     */
    fun compensate(
        aggregateId: AggregateId,
        version: Int,
        target: CompensationTarget
    ): Mono<Long> = resend(aggregateId, version, version, target)

    /**
     * Resends events for the specified version range.
     *
     * @param aggregateId The aggregate ID to resend events for
     * @param headVersion The starting version (inclusive)
     * @param tailVersion The ending version (inclusive)
     * @param target The compensation target
     * @return A Mono emitting the number of events resent
     */
    fun resend(
        aggregateId: AggregateId,
        headVersion: Int,
        tailVersion: Int,
        target: CompensationTarget
    ): Mono<Long>
}
