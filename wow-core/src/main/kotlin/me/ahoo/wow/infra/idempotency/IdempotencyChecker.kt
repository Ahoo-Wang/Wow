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

import reactor.core.publisher.Mono

/**
 * Interface for checking idempotency of operations to prevent duplicate processing.
 * Idempotency checkers determine whether a given element (typically a command or request identifier)
 * has been processed before, ensuring that operations can be safely retried without side effects.
 *
 * @author ahoo wang
 */
fun interface IdempotencyChecker {
    /**
     * Checks if the given element passes the idempotency test.
     * Returns true if the element is considered unique (not a duplicate), false if it's a duplicate.
     * The check is performed asynchronously and returns a Mono for reactive programming compatibility.
     *
     * @param element the element to check for idempotency (typically a command or request identifier)
     * @return a Mono emitting true if the element passes the idempotency check (is unique),
     *         false if it's a duplicate
     */
    fun check(element: String): Mono<Boolean>
}

/**
 * No-operation implementation of IdempotencyChecker that always allows operations to proceed.
 * This implementation always returns true, effectively disabling idempotency checking.
 * Useful for scenarios where idempotency is not required or is handled elsewhere.
 */
object NoOpIdempotencyChecker : IdempotencyChecker {
    /**
     * Pre-computed Mono that always emits true, cached for performance.
     */
    private val ALWAYS_TRUE = Mono.just(true)

    /**
     * Always returns true, indicating that all elements pass the idempotency check.
     *
     * @param element the element to check (ignored in this implementation)
     * @return a Mono that always emits true
     */
    override fun check(element: String): Mono<Boolean> = ALWAYS_TRUE
}
