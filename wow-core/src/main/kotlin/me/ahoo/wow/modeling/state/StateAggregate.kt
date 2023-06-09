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
import me.ahoo.wow.configuration.asRequiredAggregateType
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.modeling.annotation.asAggregateMetadata
import me.ahoo.wow.modeling.asAggregateId
import me.ahoo.wow.modeling.matedata.AggregateMetadata
import me.ahoo.wow.modeling.matedata.StateAggregateMetadata

/**
 * State Aggregate .
 *
 * 1. 聚合状态容器
 * 2. 订阅领域事件，修改聚合状态
 * 3. 状态聚合必须具有无参构造函数，作为序列化使用
 *
 * @author ahoo wang
 */
interface StateAggregate<S : Any> : ReadOnlyStateAggregate<S> {
    /**
     * 当聚合未找到匹配的 `onSourcing` 方法时，不会认为产生的故障，忽略该事件，但更新聚合版本号为该领域事件的版本号.
     */
    @Throws(SourcingVersionConflictException::class)
    fun onSourcing(eventStream: DomainEventStream): StateAggregate<S>

    companion object {

        @JvmStatic
        fun <S : Any> AggregateMetadata<*, S>.asStateAggregate(
            state: S,
            version: Int,
            eventId: String = "",
            firstEventTime: Long = 0,
            eventTime: Long = 0,
            deleted: Boolean = false
        ): StateAggregate<S> {
            val aggregateId = asAggregateId(this.state.aggregateIdAccessor[state])
            return this.state.asStateAggregate(
                aggregateId = aggregateId,
                state = state,
                version = version,
                eventId = eventId,
                firstEventTime = firstEventTime,
                eventTime = eventTime,
                deleted = deleted,
            )
        }

        @JvmStatic
        fun <S : Any> StateAggregateMetadata<S>.asStateAggregate(
            aggregateId: AggregateId,
            state: S,
            version: Int,
            eventId: String = "",
            firstEventTime: Long = 0,
            eventTime: Long = 0,
            deleted: Boolean = false
        ): StateAggregate<S> {
            return SimpleStateAggregate(
                aggregateId = aggregateId,
                metadata = this,
                state = state,
                version = version,
                eventId = eventId,
                firstEventTime = firstEventTime,
                eventTime = eventTime,
                deleted = deleted,
            )
        }

        @JvmStatic
        fun <S : Any> ReadOnlyStateAggregate<S>.asStateAggregate(): StateAggregate<S> {
            val metadata = aggregateId.asRequiredAggregateType<Any>()
                .asAggregateMetadata<Any, S>().state
            return metadata.asStateAggregate(
                aggregateId = aggregateId,
                state = state,
                version = version,
                eventId = eventId,
                firstEventTime = firstEventTime,
                eventTime = eventTime,
                deleted = deleted,
            )
        }
    }
}
