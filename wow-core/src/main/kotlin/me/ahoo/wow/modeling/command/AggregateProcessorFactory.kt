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

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.state.StateAggregateFactory
import me.ahoo.wow.modeling.state.StateAggregateRepository

/**
 * Factory interface for creating aggregate processors.
 *
 * Implementations of this interface are responsible for instantiating aggregate processors
 * for specific aggregate instances.
 */
interface AggregateProcessorFactory {
    /**
     * Creates an aggregate processor for the specified aggregate ID and metadata.
     *
     * @param C The type of the command aggregate root.
     * @param S The type of the state aggregate.
     * @param aggregateId The ID of the aggregate to create a processor for.
     * @param aggregateMetadata The metadata describing the aggregate.
     * @return A new aggregate processor instance.
     */
    fun <C : Any, S : Any> create(
        aggregateId: AggregateId,
        aggregateMetadata: AggregateMetadata<C, S>
    ): AggregateProcessor<C>
}

/**
 * Factory that creates retryable aggregate processors.
 *
 * This implementation provides resilience by creating processors that can handle
 * transient failures and retry operations as needed.
 *
 * @param stateAggregateFactory Factory for creating state aggregates.
 * @param stateAggregateRepository Repository for accessing state aggregates.
 * @param commandAggregateFactory Factory for creating command aggregates.
 */
class RetryableAggregateProcessorFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory
) : AggregateProcessorFactory {
    /**
     * Creates a retryable aggregate processor.
     *
     * @param C The type of the command aggregate root.
     * @param S The type of the state aggregate.
     * @param aggregateId The ID of the aggregate.
     * @param aggregateMetadata The aggregate metadata.
     * @return A new RetryableAggregateProcessor instance.
     */
    override fun <C : Any, S : Any> create(
        aggregateId: AggregateId,
        aggregateMetadata: AggregateMetadata<C, S>
    ): AggregateProcessor<C> =
        RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = aggregateMetadata,
            aggregateFactory = stateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = commandAggregateFactory,
        )
}
