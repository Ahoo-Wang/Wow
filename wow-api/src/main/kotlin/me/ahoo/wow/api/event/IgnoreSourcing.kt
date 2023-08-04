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

/**
 *  Ignore sourcing domain event to state aggregate.
 *
 *  应用场景：执行聚合根命令时，业务校验失败需要生成失败事件以便下游订阅者处理。并且不需要溯源领域事件。
 *  触发条件：
 *  - 领域事件继承 [me.ahoo.wow.api.exception.ErrorInfo] ，标记该事件为失败事件
 *  - 领域事件继承 [IgnoreSourcing]
 *  - 领域事件版本=1
 *  ---
 *  影响：
 *  - [me.ahoo.wow.modeling.state.StateAggregate.onSourcing] 忽略该领域事件的溯源，且不会变更聚合版本。
 *  - [me.ahoo.wow.eventsourcing.state.SendStateEventFilter] 将忽略未初始化的状态聚合发送到状态事件总线。
 *  - 聚合快照处理器将无法接受到该状态事件，即不会存储到快照仓库。
 */
interface IgnoreSourcing
