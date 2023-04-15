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

package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.exception.retryable
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

class RetryableAggregateProcessor<C : Any, S : Any>(
    override val aggregateId: AggregateId,
    private val aggregateMetadata: AggregateMetadata<C, S>,
    private val aggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory,
) : AggregateProcessor<C>, NamedTypedAggregate<C> by aggregateMetadata.command {
    private companion object {
        private val log = LoggerFactory.getLogger(RetryableAggregateProcessor::class.java)
        private const val MAX_RETRIES = 3L
        private val MIN_BACKOFF = Duration.ofSeconds(2)
    }

    private var aggregateCache: Mono<CommandAggregate<C, S>> = buildAggregateCache()

    private fun buildAggregateCache() = stateAggregateRepository.load(aggregateMetadata.state, aggregateId)
        .map { commandAggregateFactory.create(aggregateMetadata, it) }
        .cacheInvalidateIf { CommandState.EXPIRED == it.commandState }

    private val retryStrategy: Retry = Retry.backoff(MAX_RETRIES, MIN_BACKOFF)
        .filter {
            val retryable = it.retryable
            if (retryable && log.isWarnEnabled) {
                log.warn("Retry {}.", aggregateId, it)
            }
            retryable
        }

    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        if (exchange.message.isCreate) {
            return aggregateFactory.create(aggregateMetadata.state, exchange.message.aggregateId)
                .map { commandAggregateFactory.create(aggregateMetadata, it) }
                .flatMap { aggregate ->
                    aggregate.process(exchange)
                        .doOnSuccess {
                            aggregateCache = Mono.defer {
                                if (CommandState.EXPIRED == aggregate.commandState) {
                                    buildAggregateCache()
                                } else {
                                    Mono.just(aggregate)
                                }
                            }
                        }
                }.retryWhen(retryStrategy)
        }
        return aggregateCache
            .flatMap { it.process(exchange) }
            .retryWhen(retryStrategy)
    }
}
