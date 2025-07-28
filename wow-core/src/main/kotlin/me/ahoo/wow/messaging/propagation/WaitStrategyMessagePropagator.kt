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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.command.wait.extractWaitStrategy
import me.ahoo.wow.command.wait.stage.WaitingForStage

class WaitStrategyMessagePropagator : MessagePropagator {
    override fun propagate(header: Header, upstream: Message<*, *>) {
        val upstreamHeader = upstream.header
        val waitStrategy = upstreamHeader.extractWaitStrategy() ?: return
        if (waitStrategy.waitStrategy is WaitingForStage.Materialized && upstream !is CommandMessage<*>) {
            return
        }
        waitStrategy.waitStrategy.propagate(waitStrategy.endpoint, header)
    }
}
