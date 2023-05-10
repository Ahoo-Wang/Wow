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

package me.ahoo.wow.messaging.handler

import me.ahoo.wow.api.annotation.Order
import me.ahoo.wow.api.exception.retryable
import me.ahoo.wow.event.DomainEventFunctionFilter
import me.ahoo.wow.eventsourcing.snapshot.SnapshotFunctionFilter
import me.ahoo.wow.modeling.command.AggregateProcessorFilter
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import reactor.util.retry.RetryBackoffSpec
import java.time.Duration

val DEFAULT_RETRY_SPEC: RetryBackoffSpec = Retry.backoff(3, Duration.ofSeconds(2))
    .filter { it.retryable }

@Order(
    before = [
        AggregateProcessorFilter::class,
        DomainEventFunctionFilter::class,
        SnapshotFunctionFilter::class,
    ],
)
class RetryableFilter<T : MessageExchange<*>>(
    private val retrySpec: Retry = DEFAULT_RETRY_SPEC,
) : Filter<T> {
    override fun filter(exchange: T, next: FilterChain<T>): Mono<Void> {
        return next.filter(exchange)
            .retryWhen(retrySpec)
    }
}
