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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.exception.recoverable
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

class RetryableAggregateProcessor<C : Any, S : Any>(
    override val aggregateId: AggregateId,
    private val aggregateMetadata: AggregateMetadata<C, S>,
    private val aggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory
) : AggregateProcessor<C>, NamedTypedAggregate<C> by aggregateMetadata.command {
    private companion object {
        private val log = KotlinLogging.logger {}
        private const val MAX_RETRIES = 3L
        private val MIN_BACKOFF = Duration.ofMillis(500)
    }

    override val processorName: String = RetryableAggregateProcessor::class.simpleName!!

    private val retryStrategy: Retry = Retry.backoff(MAX_RETRIES, MIN_BACKOFF)
        .filter {
            it.recoverable == RecoverableType.RECOVERABLE
        }.doBeforeRetry {
            log.warn(it.failure()) {
                "[BeforeRetry] $aggregateId totalRetries[${it.totalRetries()}]."
            }
        }

    override fun process(exchange: ServerCommandExchange<*>): Mono<DomainEventStream> {
        val stateAggregateMono = if (exchange.message.isCreate) {
            aggregateFactory.createAsMono(aggregateMetadata.state, exchange.message.aggregateId)
        } else {
            stateAggregateRepository.load(aggregateId, aggregateMetadata.state)
        }
        return stateAggregateMono.map {
            commandAggregateFactory.create(aggregateMetadata, it)
        }
            .flatMap {
                /**
                 * remove error for retry.
                 */
                exchange.clearError()
                it.process(exchange)
            }
            .retryWhen(retryStrategy)
    }
}
