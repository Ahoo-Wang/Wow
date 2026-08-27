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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.wait.TestNamedAggregate
import me.ahoo.wow.command.wait.testAggregateId
import me.ahoo.wow.eventsourcing.NoopRequestIdExistenceChecker
import me.ahoo.wow.eventsourcing.RequestIdExistenceChecker
import me.ahoo.wow.infra.idempotency.AggregateIdempotencyCheckerProvider
import me.ahoo.wow.infra.idempotency.IdempotencyChecker
import me.ahoo.wow.modeling.materialize
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DefaultRequestIdCheckerTest {
    @Test
    fun `check returns true without existence lookup when idempotency precheck passes`() {
        val existenceChecks = AtomicInteger()
        val checker = defaultRequestIdChecker(
            idempotencyChecker = IdempotencyChecker { true },
            requestIdExistenceChecker = RequestIdExistenceChecker { _, _ ->
                existenceChecks.incrementAndGet()
                Mono.just(true)
            },
        )

        StepVerifier.create(checker.check(testAggregateId(), "request-1"))
            .expectNext(true)
            .verifyComplete()

        existenceChecks.get().assert().isEqualTo(0)
    }

    @Test
    fun `check returns true when precheck rejects but request id does not exist`() {
        val checker = defaultRequestIdChecker(
            idempotencyChecker = IdempotencyChecker { false },
            requestIdExistenceChecker = RequestIdExistenceChecker { _, _ -> Mono.just(false) },
        )

        StepVerifier.create(checker.check(testAggregateId(), "request-1"))
            .expectNext(true)
            .verifyComplete()
    }

    @Test
    fun `check returns false when precheck rejects and request id exists`() {
        val checker = defaultRequestIdChecker(
            idempotencyChecker = IdempotencyChecker { false },
            requestIdExistenceChecker = RequestIdExistenceChecker { _, _ -> Mono.just(true) },
        )

        StepVerifier.create(checker.check(testAggregateId(), "request-1"))
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun `check returns false when precheck rejects with noop existence checker`() {
        val checker = defaultRequestIdChecker(
            idempotencyChecker = IdempotencyChecker { false },
            requestIdExistenceChecker = NoopRequestIdExistenceChecker,
        )

        StepVerifier.create(checker.check(testAggregateId(), "request-1"))
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun `check returns false with default noop existence checker when precheck rejects`() {
        val checker = DefaultRequestIdChecker(
            idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider {
                IdempotencyChecker { false }
            },
        )

        StepVerifier.create(checker.check(testAggregateId(), "request-1"))
            .expectNext(false)
            .verifyComplete()
    }

    @Test
    fun `check resolves idempotency checker by materialized named aggregate`() {
        val requestedNamedAggregate = AtomicReference<Any>()
        val checker = DefaultRequestIdChecker(
            idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider { namedAggregate ->
                requestedNamedAggregate.set(namedAggregate)
                IdempotencyChecker { true }
            },
        )

        StepVerifier.create(checker.check(testAggregateId(), "request-1"))
            .expectNext(true)
            .verifyComplete()

        requestedNamedAggregate.get().assert().isEqualTo(TestNamedAggregate.materialize())
    }

    @Test
    fun `check evaluates idempotency precheck for each subscription`() {
        val idempotencyChecks = AtomicInteger()
        val checker = defaultRequestIdChecker(
            idempotencyChecker = IdempotencyChecker {
                idempotencyChecks.incrementAndGet()
                true
            },
            requestIdExistenceChecker = RequestIdExistenceChecker { _, _ -> Mono.just(true) },
        )
        val result = checker.check(testAggregateId(), "request-1")

        StepVerifier.create(result)
            .expectNext(true)
            .verifyComplete()
        StepVerifier.create(result)
            .expectNext(true)
            .verifyComplete()

        idempotencyChecks.get().assert().isEqualTo(2)
    }

    private fun defaultRequestIdChecker(
        idempotencyChecker: IdempotencyChecker,
        requestIdExistenceChecker: RequestIdExistenceChecker
    ): DefaultRequestIdChecker {
        return DefaultRequestIdChecker(
            idempotencyCheckerProvider = AggregateIdempotencyCheckerProvider { idempotencyChecker },
            requestIdExistenceChecker = requestIdExistenceChecker,
        )
    }
}
