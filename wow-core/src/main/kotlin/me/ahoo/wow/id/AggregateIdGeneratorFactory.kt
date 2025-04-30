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

package me.ahoo.wow.id

import me.ahoo.cosid.IdGenerator
import me.ahoo.wow.api.modeling.NamedAggregate

/**
 * 定义一个函数式接口，用于生成聚合标识符的工厂。
 *
 * 本接口的主要作用是提供一个标准方法，根据聚合根的名称生成相应的标识符生成器。
 * 这种设计允许在领域驱动设计（DDD）中灵活地为不同的聚合根生成全局唯一的标识符，
 * 从而确保聚合根的标识符生成策略可以集中管理和配置。
 *
 * @see NamedAggregate 代表具有特定名称的聚合根。
 * @see IdGenerator 用于生成聚合标识符的接口。
 */
fun interface AggregateIdGeneratorFactory {

    /**
     * 根据指定的聚合根生成一个标识符生成器。
     *
     * 此方法允许根据聚合根的名称动态决定使用哪种标识符生成策略。
     * 如果给定的聚合根不支持标识符生成或没有特定的生成策略，则返回null。
     *
     * @param namedAggregate 聚合根实例，包含聚合的名称信息。
     * @return 可能返回null的标识符生成器。
     */
    fun create(namedAggregate: NamedAggregate): IdGenerator?
}
