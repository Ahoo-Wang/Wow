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

package me.ahoo.wow.messaging.propagation

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message

/**
 * 消息传播器接口，用于将上游消息的上下文信息传播到指定的头部信息中
 */
interface MessagePropagator {
    /**
     * 将上游消息的上下文信息传播到目标头部
     *
     * @param header 目标消息的头部信息，用于接收传播的上下文数据
     * @param upstream 上游消息，提供需要传播的上下文信息
     */
    fun propagate(header: Header, upstream: Message<*, *>)
}
