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

/**
 * 接口定义了拥有聚合ID能力的实体应遵循的规范
 * 聚合ID是用来唯一标识一个聚合的字段，聚合是一组相关的实体和值对象的集合，被视为一个整体单元
 * 在领域驱动设计（DDD）中，聚合是保持数据一致性的基本单位
 *
 * @property aggregateId 聚合ID，用于唯一标识一个聚合实例
 */
interface AggregateIdCapable {
    val aggregateId: AggregateId
}
