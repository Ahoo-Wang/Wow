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

package me.ahoo.wow.opentelemetry.messaging

import io.opentelemetry.context.propagation.TextMapGetter
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.messaging.handler.MessageExchange

class MessageExchangeTextMapGetter<E : MessageExchange<*, Message<*, *>>> : TextMapGetter<E> {
    override fun keys(carrier: E): Iterable<String> {
        return carrier.message.header.keys
    }

    override fun get(carrier: E?, key: String): String? {
        if (carrier == null) {
            return null
        }
        return carrier.message.header.get(key)
    }
}
