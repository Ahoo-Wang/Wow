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
package me.ahoo.wow.modeling.command

import me.ahoo.wow.api.Version
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.modeling.NamedTypedAggregate
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.state.StateAggregate
import reactor.core.publisher.Mono

/**
 * Command Aggregate .
 *
 * 1. 订阅命令消息
 * 2. 依赖状态聚合持有的聚合状态，验证业务规则
 * 3. 发布领域事件
 *
 * 订阅命令消息，并依赖状态聚合的当前状态验证业务规则.
 *
 * @author ahoo wang
 */
interface CommandAggregate<C : Any, S : Any> : NamedTypedAggregate<C>, AggregateProcessor<C>, Version {
    override val aggregateId: AggregateId
        get() = state.aggregateId
    override val version: Int
        get() = state.version

    val state: StateAggregate<S>
    val commandRoot: C
    val commandState: CommandState
}

enum class CommandState {
    STORED {
        override fun onSourcing(
            stateAggregate: StateAggregate<*>,
            eventStream: DomainEventStream
        ): CommandState {
            stateAggregate.onSourcing(eventStream)
            return SOURCED
        }
    },
    SOURCED {
        override fun onStore(eventStore: EventStore, eventStream: DomainEventStream): Mono<CommandState> {
            return eventStore.append(eventStream)
                .checkpoint(
                    "Append DomainEventStream[${eventStream.id}] CommandId:${eventStream.commandId} [CommandState]"
                )
                .thenReturn(STORED)
        }
    },
    EXPIRED
    ;

    open fun onSourcing(stateAggregate: StateAggregate<*>, eventStream: DomainEventStream): CommandState {
        throw UnsupportedOperationException(
            "Failed to Sourcing eventStream[${eventStream.id}]: Current State[$this] does not support this operation.",
        )
    }

    open fun onStore(eventStore: EventStore, eventStream: DomainEventStream): Mono<CommandState> {
        throw UnsupportedOperationException(
            "Failed to Store eventStream[${eventStream.id}]: Current State[$this] does not support this operation.",
        )
    }
}
