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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.OwnerId
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.api.modeling.SpaceIdCapable
import me.ahoo.wow.api.modeling.TenantId
import me.ahoo.wow.configuration.requiredAggregateType
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata
import reactor.core.publisher.Mono

/**
 * Aggregate Factory .
 * 创建一个未初始化/空状态的聚合.
 *
 * @author ahoo wang
 */
interface StateAggregateFactory {
    fun <S : Any> create(metadata: StateAggregateMetadata<S>, aggregateId: AggregateId): StateAggregate<S>
    fun <S : Any> createAsMono(metadata: StateAggregateMetadata<S>, aggregateId: AggregateId): Mono<StateAggregate<S>> {
        return Mono.fromCallable {
            create(metadata, aggregateId)
        }
    }
}

object ConstructorStateAggregateFactory : StateAggregateFactory {
    private val log = KotlinLogging.logger { }

    override fun <S : Any> create(
        metadata: StateAggregateMetadata<S>,
        aggregateId: AggregateId
    ): StateAggregate<S> {
        val stateRoot = metadata.constructState(aggregateId)
        return create(
            metadata = metadata,
            aggregateId = aggregateId,
            state = stateRoot,
            version = Version.UNINITIALIZED_VERSION
        )
    }

    fun <S : Any> create(
        metadata: StateAggregateMetadata<S>,
        aggregateId: AggregateId,
        state: S,
        version: Int,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
        eventId: String = "",
        firstOperator: String = "",
        operator: String = "",
        firstEventTime: Long = 0,
        eventTime: Long = 0,
        deleted: Boolean = false
    ): StateAggregate<S> {
        log.debug {
            "Create $aggregateId."
        }
        val stateAggregate = SimpleStateAggregate(
            aggregateId = aggregateId,
            ownerId = ownerId,
            spaceId = spaceId,
            metadata = metadata,
            state = state,
            version = version,
            eventId = eventId,
            firstOperator = firstOperator,
            operator = operator,
            firstEventTime = firstEventTime,
            eventTime = eventTime,
            deleted = deleted,
        )
        if (state is ReadOnlyStateAggregateAware<*>) {
            @Suppress("UNCHECKED_CAST")
            val aware = state as ReadOnlyStateAggregateAware<S>
            aware.setReadOnlyStateAggregate(stateAggregate)
        }
        return stateAggregate
    }

    private fun <S : Any> StateAggregateMetadata<S>.constructState(aggregateId: AggregateId): S {
        return when (constructorAccessor.constructor.parameterCount) {
            0 -> constructorAccessor.invoke(arrayOf())
            1 -> constructorAccessor.invoke(arrayOf(aggregateId.id))
            2 -> constructorAccessor.invoke(arrayOf(aggregateId.id, aggregateId.tenantId))
            else -> {
                throw IllegalArgumentException("Unsupported constructor parameters.")
            }
        }
    }

    @JvmStatic
    fun <S : Any> StateAggregateMetadata<S>.toStateAggregate(
        aggregateId: AggregateId,
        state: S,
        version: Int,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
        eventId: String = "",
        firstOperator: String = "",
        operator: String = "",
        firstEventTime: Long = 0,
        eventTime: Long = 0,
        deleted: Boolean = false
    ): StateAggregate<S> {
        return create(
            metadata = this,
            aggregateId = aggregateId,
            state = state,
            version = version,
            ownerId = ownerId,
            spaceId = spaceId,
            eventId = eventId,
            firstOperator = firstOperator,
            operator = operator,
            firstEventTime = firstEventTime,
            eventTime = eventTime,
            deleted = deleted
        )
    }

    @JvmStatic
    fun <S : Any> AggregateMetadata<*, S>.toStateAggregate(
        state: S,
        version: Int,
        ownerId: String = OwnerId.DEFAULT_OWNER_ID,
        spaceId: SpaceId = SpaceIdCapable.DEFAULT_SPACE_ID,
        eventId: String = "",
        firstOperator: String = "",
        operator: String = "",
        firstEventTime: Long = 0,
        eventTime: Long = 0,
        deleted: Boolean = false,
        aggregateId: String = "",
        tenantId: String = TenantId.DEFAULT_TENANT_ID
    ): StateAggregate<S> {
        val aggregateId = extractAggregateId(state, aggregateId, tenantId)
        return this.state.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = version,
            ownerId = ownerId,
            spaceId = spaceId,
            eventId = eventId,
            firstOperator = firstOperator,
            operator = operator,
            firstEventTime = firstEventTime,
            eventTime = eventTime,
            deleted = deleted,
        )
    }

    @JvmStatic
    fun <S : Any> ReadOnlyStateAggregate<S>.toStateAggregate(): StateAggregate<S> {
        val metadata = aggregateId.requiredAggregateType<Any>()
            .aggregateMetadata<Any, S>().state
        return metadata.toStateAggregate(
            aggregateId = aggregateId,
            state = state,
            version = version,
            ownerId = ownerId,
            spaceId = spaceId,
            eventId = eventId,
            firstOperator = firstOperator,
            operator = operator,
            firstEventTime = firstEventTime,
            eventTime = eventTime,
            deleted = deleted,
        )
    }
}
