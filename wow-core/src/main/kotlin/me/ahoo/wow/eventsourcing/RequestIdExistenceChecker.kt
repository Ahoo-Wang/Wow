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

package me.ahoo.wow.eventsourcing

import me.ahoo.wow.api.modeling.AggregateId
import reactor.core.publisher.Mono

/**
 * Checks whether a request ID already exists for a specific aggregate.
 *
 * This is the authoritative existence check used after probabilistic idempotency prechecks.
 */
fun interface RequestIdExistenceChecker {
    /**
     * Checks whether the request ID already exists for the specified aggregate.
     *
     * @param aggregateId the aggregate ID to check
     * @param requestId the request identifier to check
     * @return a Mono emitting true if the request ID already exists for this aggregate
     */
    fun existsRequestId(
        aggregateId: AggregateId,
        requestId: String
    ): Mono<Boolean>
}

/**
 * The existence checker of a node that has no event store, such as a gateway-only service.
 *
 * It answers "absent": such a node cannot know, and the authoritative check runs where the command is processed,
 * against the event store, before the command handler runs (`DefaultCommandHandler`), with the event store's unique
 * request ID as the last guard at append. Since 9.3.0; it used to answer "exists", so a Bloom-filter false positive on
 * a gateway-only service rejected a legitimate command, and a resent command was rejected on the gateway only.
 */
object NoopRequestIdExistenceChecker : RequestIdExistenceChecker {
    private val ABSENT = Mono.just(false)

    override fun existsRequestId(
        aggregateId: AggregateId,
        requestId: String
    ): Mono<Boolean> = ABSENT
}
