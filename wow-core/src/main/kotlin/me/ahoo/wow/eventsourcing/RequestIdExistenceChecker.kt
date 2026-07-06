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
 * No-op implementation used when no authoritative request ID existence checker is available.
 *
 * It fails closed by reporting the request ID as existing, preserving duplicate protection when
 * a probabilistic precheck rejects a request ID but no event store can confirm it.
 */
object NoopRequestIdExistenceChecker : RequestIdExistenceChecker {
    private val EXISTS = Mono.just(true)

    override fun existsRequestId(
        aggregateId: AggregateId,
        requestId: String
    ): Mono<Boolean> = EXISTS
}
