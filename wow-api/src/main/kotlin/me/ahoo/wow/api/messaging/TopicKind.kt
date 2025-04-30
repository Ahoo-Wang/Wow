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

package me.ahoo.wow.api.messaging

/**
 * TopicKind 枚举类定义了主题的种类
 * 这些种类用于标识主题的性质或用途
 */
enum class TopicKind {
    // 未定义类型，用于标识未知或未分类的主题
    UNDEFINED,

    // 命令类型，表示该主题用于发送命令或指令
    COMMAND,

    // 事件流类型，表示该主题用于传输一系列连续的事件
    EVENT_STREAM,

    // 状态事件类型，表示该主题用于传输状态变化事件
    STATE_EVENT
}

/**
 * TopicKindCapable 接口定义了主题种类感知能力
 * 实现该接口的类必须提供主题种类属性，以供外部查询
 */
interface TopicKindCapable {
    // 获取主题种类的属性，具体种类由实现类决定
    val topicKind: TopicKind
}
