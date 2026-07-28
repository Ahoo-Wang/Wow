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

package me.ahoo.wow.runtime.internal

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscription
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class ShutdownSubscriptionBoundaryTest {
    @Test
    fun `rejected cleanup clears callbacks and isolates late signals`() {
        val cleanupDispatches = AtomicInteger()
        val completions = AtomicInteger()
        val errors = AtomicInteger()
        val subscription = RecordingSubscription()
        val boundary = ShutdownSubscriptionBoundary(
            cleanupDispatcher = RuntimeCleanupDispatcher {
                cleanupDispatches.incrementAndGet()
                false
            },
            onComplete = completions::incrementAndGet,
            onError = { errors.incrementAndGet() },
        )
        boundary.onSubscribe(subscription)

        boundary.detach().assert()
            .isEqualTo(CleanupDispatchResult.REJECTED)
        boundary.onComplete()
        boundary.onError(IllegalStateException("late"))

        cleanupDispatches.get().assert().isEqualTo(1)
        subscription.requested.get().assert().isEqualTo(Long.MAX_VALUE)
        subscription.cancellations.get().assert().isZero()
        completions.get().assert().isZero()
        errors.get().assert().isZero()
    }

    private class RecordingSubscription : Subscription {
        val requested = AtomicLong()
        val cancellations = AtomicInteger()

        override fun request(elements: Long) {
            requested.set(elements)
        }

        override fun cancel() {
            cancellations.incrementAndGet()
        }
    }
}
