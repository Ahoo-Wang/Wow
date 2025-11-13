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

package me.ahoo.wow.infra.idempotency

import me.ahoo.wow.api.modeling.NamedAggregate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Provider interface for obtaining idempotency checkers specific to named aggregates.
 * Allows different aggregates to have their own idempotency checking strategies,
 * which is useful for scenarios where different aggregates have different
 * idempotency requirements or performance characteristics.
 */
fun interface AggregateIdempotencyCheckerProvider {
    /**
     * Gets the idempotency checker for the specified named aggregate.
     * Implementations may return shared or aggregate-specific checkers.
     *
     * @param namedAggregate the named aggregate for which to get the checker
     * @return the idempotency checker for the aggregate
     */
    fun getChecker(namedAggregate: NamedAggregate): IdempotencyChecker
}

/**
 * Default implementation of AggregateIdempotencyCheckerProvider that caches checkers per aggregate.
 * Uses a supplier function to create checkers on demand and caches them for future use.
 * This ensures that each named aggregate gets its own idempotency checker instance
 * while avoiding redundant checker creation.
 *
 * @param checkerSupplier function that creates an idempotency checker for a given named aggregate
 */
class DefaultAggregateIdempotencyCheckerProvider(
    private val checkerSupplier: (NamedAggregate) -> IdempotencyChecker
) : AggregateIdempotencyCheckerProvider {
    /**
     * Thread-safe cache mapping named aggregates to their corresponding idempotency checkers.
     */
    private val idempotencyCheckers: ConcurrentMap<NamedAggregate, IdempotencyChecker> = ConcurrentHashMap()

    /**
     * Gets the cached idempotency checker for the named aggregate, creating one if it doesn't exist.
     * Uses computeIfAbsent to ensure thread-safe lazy initialization.
     *
     * @param namedAggregate the named aggregate for which to get the checker
     * @return the idempotency checker for the aggregate, either cached or newly created
     */
    override fun getChecker(namedAggregate: NamedAggregate): IdempotencyChecker =
        idempotencyCheckers.computeIfAbsent(namedAggregate) { checkerSupplier(namedAggregate) }
}
