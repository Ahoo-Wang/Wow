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

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Aggregate Factory .
 * 创建一个未初始化/空状态的聚合.
 *
 * @author ahoo wang
 */
interface StateAggregateFactory {
    fun <S : Any> create(metadata: StateAggregateMetadata<S>, aggregateId: AggregateId): Mono<StateAggregate<S>>
}

object ConstructorStateAggregateFactory : StateAggregateFactory {
    private val log: Logger = LoggerFactory.getLogger(ConstructorStateAggregateFactory::class.java)

    fun <S : Any> createStateAggregate(
        metadata: StateAggregateMetadata<S>,
        aggregateId: AggregateId
    ): StateAggregate<S> {
        if (log.isDebugEnabled) {
            log.debug("Create {}.", aggregateId)
        }
        val stateRoot = metadata.constructorAccessor.invoke(arrayOf(aggregateId.id))
        return SimpleStateAggregate(
            aggregateId = aggregateId,
            metadata = metadata,
            version = Version.UNINITIALIZED_VERSION,
            state = stateRoot,
        )
    }

    override fun <S : Any> create(
        metadata: StateAggregateMetadata<S>,
        aggregateId: AggregateId
    ): Mono<StateAggregate<S>> {
        return Mono.fromCallable {
            createStateAggregate(metadata, aggregateId)
        }
    }
}
