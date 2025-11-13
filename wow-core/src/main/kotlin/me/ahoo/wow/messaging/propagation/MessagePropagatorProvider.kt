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

/**
 * Provider that aggregates and delegates to all available message propagators.
 *
 * This provider uses Java ServiceLoader to discover and load all MessagePropagator
 * implementations, then delegates propagation calls to each of them in order.
 */
object MessagePropagatorProvider : MessagePropagator {
    private val log = KotlinLogging.logger {}

    /**
     * Lazily loaded list of all available message propagators.
     *
     * Uses ServiceLoader to discover propagators and sorts them by order annotation.
     */
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

    /**
     * Propagates context using all loaded propagators.
     *
     * @param header The target header to propagate to
     * @param upstream The upstream message to propagate from
     */
    override fun propagate(
        header: Header,
        upstream: Message<*, *>
    ) {
        messagePropagators.forEach { it.propagate(header, upstream) }
    }

    /**
     * Extension function to propagate context to this header from an upstream message.
     *
     * @param upstream The upstream message to propagate from
     * @return This header with propagated context
     */
    fun Header.propagate(upstream: Message<*, *>): Header {
        propagate(this, upstream)
        return this
    }
}
