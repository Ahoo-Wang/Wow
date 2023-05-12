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
import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.infra.Decorator.Companion.getDelegate
import me.ahoo.wow.messaging.LocalSendMessageBus
import me.ahoo.wow.messaging.handler.MessageExchange

@Suppress("MemberNameEqualsClassName")
object Tracing {

    const val PARENT_CONTEXT_KEY = "__TRACING_PARENT_CONTEXT__"
    fun <E : MessageExchange<*>> E.setParentContext(parentContext: Context): E {
        attributes[PARENT_CONTEXT_KEY] = parentContext
        return this
    }

    fun <E : MessageExchange<*>> E.getParentContext(): Context? {
        return attributes[PARENT_CONTEXT_KEY] as Context?
    }

    fun CommandBus.tracing(): CommandBus {
        if (this is TracingMessageBus<*>) {
            return this
        }
        if (this.getDelegate() is LocalSendMessageBus<*, *>) {
            return TracingLocalCommandBus(this)
        }
        return TracingDistributedCommandBus(this)
    }

    fun DomainEventBus.tracing(): DomainEventBus {
        if (this is TracingMessageBus<*>) {
            return this
        }
        if (this.getDelegate() is LocalSendMessageBus<*, *>) {
            return TracingLocalEventBus(this)
        }
        return TracingDistributedEventBus(this)
    }
}