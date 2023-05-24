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

interface AggregateProcessorFactory {
    fun <C : Any, S : Any> create(
        aggregateId: AggregateId,
        aggregateMetadata: AggregateMetadata<C, S>
    ): AggregateProcessor<C>
}

class RetryableAggregateProcessorFactory(
    private val stateAggregateFactory: StateAggregateFactory,
    private val stateAggregateRepository: StateAggregateRepository,
    private val commandAggregateFactory: CommandAggregateFactory
) :
    AggregateProcessorFactory {
    override fun <C : Any, S : Any> create(
        aggregateId: AggregateId,
        aggregateMetadata: AggregateMetadata<C, S>
    ): AggregateProcessor<C> {
        return RetryableAggregateProcessor(
            aggregateId = aggregateId,
            aggregateMetadata = aggregateMetadata,
            aggregateFactory = stateAggregateFactory,
            stateAggregateRepository = stateAggregateRepository,
            commandAggregateFactory = commandAggregateFactory,
        )
    }
}
