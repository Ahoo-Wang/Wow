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
import me.ahoo.wow.api.messaging.function.FunctionInfo
import java.util.ServiceLoader

/**
 * The header propagation of one runtime: the [propagators] it runs, in `@Order`, whenever a message is derived from
 * an upstream one (an event stream from its command, a command from the event a saga reacts to).
 *
 * The runtime gets it injected: the Spring starter builds one bean from the ServiceLoader contributions
 * ([loadServices]) and the application's `MessagePropagator` beans. [DEFAULT] holds the ServiceLoader contributions
 * alone, for code that is not wired by a container. The set of keys the default propagators write is a frozen v9
 * wire contract, locked by the wire-format golden tests.
 */
class MessagePropagators(propagators: List<MessagePropagator>) {
    companion object {
        private val log = KotlinLogging.logger {}

        /** The ServiceLoader contributions: the framework's propagators and those of the modules on the classpath. */
        fun loadServices(): List<MessagePropagator> =
            ServiceLoader.load(MessagePropagator::class.java).toList()

        /** The registry of the ServiceLoader contributions, for code that is not wired by a container. */
        val DEFAULT: MessagePropagators by lazy { MessagePropagators(loadServices()) }
    }

    val propagators: List<MessagePropagator> = propagators.sortedByOrder().onEach {
        log.info { "Load MessagePropagator: [${it.javaClass.name}]" }
    }

    /** Runs every propagator for a message derived from [upstream]. */
    fun propagate(header: Header, upstream: Message<*, *>) {
        propagators.forEach { it.propagate(header, upstream) }
    }

    /** Runs every propagator for a message that [producer] derives from [upstream]. */
    fun propagate(header: Header, upstream: Message<*, *>, producer: FunctionInfo) {
        propagators.forEach { it.propagate(header, upstream, producer) }
    }
}

/** Propagates [upstream]'s context to this header with [propagators], and returns this header. */
fun Header.propagate(upstream: Message<*, *>, propagators: MessagePropagators = MessagePropagators.DEFAULT): Header {
    propagators.propagate(this, upstream)
    return this
}
