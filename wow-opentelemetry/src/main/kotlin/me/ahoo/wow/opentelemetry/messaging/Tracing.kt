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

import io.opentelemetry.context.Context
import me.ahoo.wow.command.DistributedCommandBus
import me.ahoo.wow.command.LocalCommandBus
import me.ahoo.wow.event.DistributedDomainEventBus
import me.ahoo.wow.event.LocalDomainEventBus
import me.ahoo.wow.messaging.handler.MessageExchange

@Suppress("MemberNameEqualsClassName")
object Tracing {

    private const val PARENT_CONTEXT_KEY = "__TRACING_PARENT_CONTEXT__"
    fun <E : MessageExchange<*, *>> E.setParentContext(parentContext: Context): E {
        attributes[PARENT_CONTEXT_KEY] = parentContext
        return this
    }

    fun <E : MessageExchange<*, *>> E.getParentContext(): Context? {
        return attributes[PARENT_CONTEXT_KEY] as Context?
    }

    fun LocalCommandBus.tracing(): LocalCommandBus {
        return tracing {
            TracingLocalCommandBus(this)
        }
    }

    fun DistributedCommandBus.tracing(): DistributedCommandBus {
        return tracing {
            TracingDistributedCommandBus(this)
        }
    }

    fun LocalDomainEventBus.tracing(): LocalDomainEventBus {
        return tracing {
            TracingLocalEventBus(this)
        }
    }

    fun DistributedDomainEventBus.tracing(): DistributedDomainEventBus {
        return tracing {
            TracingDistributedEventBus(this)
        }
    }

    fun <T : Any> T.tracing(): Any {
        return when (this) {
            is LocalCommandBus -> tracing()
            is DistributedCommandBus -> tracing()
            is LocalDomainEventBus -> tracing()
            is DistributedDomainEventBus -> tracing()
            else -> this
        }
    }

    inline fun <T> T.tracing(block: (T) -> T): T {
        if (this is TracingMessageBus<*>) {
            return this
        }
        return block(this)
    }
}
