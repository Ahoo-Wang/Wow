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

import me.ahoo.wow.event.DomainEventStream

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
}
