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

package me.ahoo.wow.spi.subscription

import me.ahoo.wow.api.annotation.WowSpi

/**
 * How a subscription answers a failure of its handler (9.x `RecoverableType`).
 */
enum class FailureClass {
    /**
     * The aggregate's own failure, which no retry changes (invalid data, a defect, a value refused for good): the
     * aggregate is parked at once, without a retry.
     */
    UNRECOVERABLE,

    /**
     * The aggregate's own failure, which a retry may get past (a conflict, a slow call): the handler is retried within
     * its budget (`@Retry`, `wow.subscription.retry.*`), then the aggregate is parked.
     */
    RECOVERABLE,

    /**
     * Not the aggregate's failure but its surroundings' (a store, a broker or a downstream service that is not
     * reachable): every aggregate of the partition would fail the same way, so the subscription pauses the whole
     * partition and retries until it is back, spending no aggregate's budget and parking none.
     */
    SYSTEMIC,

    /**
     * What the handler called is up but overloaded and asked it to come back later: a node refusing a
     * command as busy (`wow.command.busy`), a wait that ran out (`wow.wait.timeout`), a query store's bulkhead
     * (`wow.query.busy`), any 429 with a `Retry-After`. Never systemic: the subscription retries the entry with
     * backoff and jitter (the `Retry-After` at least), throttles the partition's concurrency until handlings succeed
     * again, and parks the aggregate once the backpressure budget is spent; it never pauses the partition.
     */
    BACKPRESSURE,
}

/**
 * Classifies the failures of subscription handlers (9.x `RecoverableExceptionProvider`): the application's hook, for
 * the failures of what its handlers call directly (a driver, a client of another system).
 *
 * The runtime asks, for the failure and then each of its causes in turn, every classifier the host gives it, in
 * order, and the first class one gives decides. A handler's own `@Retry(recoverable, unrecoverable)` comes before them
 * all; a failure none classifies is [FailureClass.RECOVERABLE]. The runtime classifies itself, after them, the SPI's
 * typed failures the backends throw ([me.ahoo.wow.spi.StorageUnavailableException] and
 * [me.ahoo.wow.spi.transport.TransportUnavailableException] systemic, [me.ahoo.wow.spi.StorageRefusedException]
 * unrecoverable), the JDK's connection failures (a socket refused or reset, a host that does not resolve, a JDBC
 * connection that failed, SQL state class `08`) and its own ones; a driver's other failures are the application's to
 * classify.
 *
 * A classifier is called on the handler's path for each failed attempt: it decides from the exception alone, quickly
 * and without blocking.
 */
@WowSpi
fun interface FailureClassifier {
    /** The class of [failure], or null when this classifier does not know it. */
    fun classify(failure: Throwable): FailureClass?

    companion object {
        /** Classifies nothing. */
        @JvmField
        val NONE: FailureClassifier = FailureClassifier { null }
    }
}
