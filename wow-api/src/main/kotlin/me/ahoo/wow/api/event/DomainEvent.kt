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

package me.ahoo.wow.api.event

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.command.CommandId
import me.ahoo.wow.api.messaging.NamedMessage
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.api.modeling.NamedAggregate

const val DEFAULT_EVENT_SEQUENCE = 1

/**
 * DomainEvent .
 *
 * Events published when a command is processed by the aggregate
 *
 * 由聚合发布的领域事件 .
 *
 * 领域事件推荐使用声明式（Declarative）设计的方式（幂等，类似于 Kubernetes apply、Docker 镜像层）
 * 即聚合根在事件朔源时只需要简单的将领域事件作为覆盖层（onSourcing 只对状态赋值，没有逻辑判断），
 * 事件朔源将可以不需要显式定义朔源函数。
 * @author ahoo wang
 */
interface DomainEvent<T : Any> :
    NamedMessage<DomainEvent<T>, T>,
    AggregateIdCapable,
    CommandId,
    NamedAggregate,
    Version,
    Revision {
    override val aggregateId: AggregateId
    val sequence: Int
        get() = DEFAULT_EVENT_SEQUENCE
    override val revision: String
        get() = DEFAULT_REVISION

    /**
     * 是否为事件流的最后一个事件
     */
    val isLast: Boolean
        get() = true
}
