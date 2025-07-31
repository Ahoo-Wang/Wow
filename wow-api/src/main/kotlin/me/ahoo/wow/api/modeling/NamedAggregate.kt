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

package me.ahoo.wow.api.modeling

import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.naming.NamedBoundedContext

interface AggregateNameCapable {
    /**
     * 聚合根的名称
     */
    val aggregateName: String
}

/**
 * 一个在特定上下文中具有唯一名称的聚合根。
 * 它继承自NamedBoundedContext，以获取上下文名称，并额外定义了聚合根名称。
 * @see me.ahoo.wow.command.CommandBus
 * @see me.ahoo.wow.eventsourcing.EventStore
 */
interface NamedAggregate : NamedBoundedContext, AggregateNameCapable {

    /**
     * 检查两个聚合根是否属于同一个上下文并具有相同的聚合根名称。
     *
     * @param other 另一个NamedAggregate实例，用于比较。
     * @return 如果两个聚合根属于同一个上下文且名称相同，则返回true；否则返回false。
     */
    fun isSameAggregateName(other: NamedAggregate): Boolean {
        return contextName == other.contextName && aggregateName == other.aggregateName
    }
}

/**
 * NamedAggregateDecorator接口定义了一个装饰器模式的命名聚合根。
 * 它继承自NamedAggregate，并委托实际的命名聚合根实现。
 * 这个接口允许在不修改原有聚合根逻辑的情况下，动态添加功能。
 */
interface NamedAggregateDecorator : NamedAggregate {
    /**
     * 被装饰的命名聚合根。
     */
    val namedAggregate: NamedAggregate

    @get:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    @get:JsonIgnore
    override val contextName: String
        get() = namedAggregate.contextName

    @get:Schema(accessMode = Schema.AccessMode.READ_ONLY)
    @get:JsonIgnore
    override val aggregateName: String
        get() = namedAggregate.aggregateName
}
