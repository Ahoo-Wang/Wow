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

package me.ahoo.wow.command

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.eventsourcing.NoopRequestIdExistenceChecker
import me.ahoo.wow.eventsourcing.RequestIdExistenceChecker
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.modeling.materialize
import reactor.core.publisher.Mono

fun interface RequestIdChecker {
    /**
     * Returns true when the request ID can continue processing.
     */
    fun check(aggregateId: AggregateId, requestId: String): Mono<Boolean>
}

class DefaultRequestIdChecker(
    private val idempotencyCheckerProvider: AggregateIdempotencyCheckerProvider,
    private val requestIdExistenceChecker: RequestIdExistenceChecker = NoopRequestIdExistenceChecker,
) : RequestIdChecker {
    override fun check(aggregateId: AggregateId, requestId: String): Mono<Boolean> = Mono.defer {
        val idempotencyChecker = idempotencyCheckerProvider
            .getChecker(aggregateId.namedAggregate.materialize())
        if (idempotencyChecker.check(requestId)) {
            return@defer Mono.just(true)
        }
        requestIdExistenceChecker.existsRequestId(aggregateId, requestId)
            .map { exists -> !exists }
    }
}
