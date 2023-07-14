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
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.TypedAggregate

interface ReadOnlyStateAggregate<S : Any> : AggregateIdCapable, Version, TypedAggregate<S> {
    override val aggregateId: AggregateId

    val state: S

    /**
     * State Aggregation Type
     */
    override val aggregateType: Class<S>

    /**
     * 用于生成领域事件版本号.
     */
    override val version: Int

    val expectedNextVersion: Int
        get() = version + 1

    /**
     * 状态聚合是否已删除
     */
    val deleted: Boolean

    //region DomainEventStream State
    val eventId: String
    val firstOperator: String
    val operator: String
    val firstEventTime: Long
    val eventTime: Long
    //endregion
}
