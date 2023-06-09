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
package me.ahoo.wow.modeling.state

import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.configuration.asAggregateType
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import reactor.core.publisher.Mono

/**
 * Aggregate Repository .
 *
 * @author ahoo wang
 */
interface StateAggregateRepository {
    /**
     * Load State Aggregate.
     *
     * `stateAggregate.initialized=false` means that no aggregate was found.
     */
    fun <S : Any> load(metadata: StateAggregateMetadata<S>, aggregateId: AggregateId): Mono<StateAggregate<S>>

    fun <S : Any> load(aggregateId: AggregateId): Mono<StateAggregate<S>> {
        val stateAggregateMetadata = checkNotNull(aggregateId.asAggregateType<Any>())
            .asAggregateMetadata<Any, S>()
            .state
        return load(stateAggregateMetadata, aggregateId)
    }
}
