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

import io.opentelemetry.context.propagation.TextMapSetter
import me.ahoo.wow.api.messaging.Message
import org.slf4j.LoggerFactory

class MessageTextMapSetter<M : Message<*, *>> : TextMapSetter<M> {
    companion object {
        private val log = LoggerFactory.getLogger(MessageTextMapSetter::class.java)
    }

    override fun set(carrier: M?, key: String, value: String) {
        if (carrier == null) {
            return
        }
        if (carrier.isReadOnly) {
            if (log.isWarnEnabled) {
                log.warn("carrier is read only. key:[{}],value:[{}]", key, value)
            }
            return
        }
        carrier.header[key] = value
    }
}
