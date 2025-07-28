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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.annotation.sortedByOrder
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import java.util.*

object MessagePropagatorProvider : MessagePropagator {
    private val log = KotlinLogging.logger {}

    private val messagePropagators: List<MessagePropagator> by lazy {
        ServiceLoader.load(MessagePropagator::class.java)
            .sortedByOrder()
            .map {
                log.info {
                    "Load MessagePropagator: [${it.javaClass.name}]"
                }
                it
            }
    }

    override fun propagate(header: Header, upstream: Message<*, *>) {
        messagePropagators.forEach { it.propagate(header, upstream) }
    }

    fun Header.propagate(upstream: Message<*, *>): Header {
        propagate(this, upstream)
        return this
    }
}
