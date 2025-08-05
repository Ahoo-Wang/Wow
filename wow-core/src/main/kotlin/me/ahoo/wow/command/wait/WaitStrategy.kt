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

package me.ahoo.wow.command.wait

import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.naming.CompletedCapable
import me.ahoo.wow.messaging.propagation.MessagePropagator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.function.Consumer

/**
 * 等待策略传播器接口
 *
 * 定义了命令等待策略中关于传播行为的抽象方法，用于控制命令处理结果的传播逻辑。
 */
interface WaitStrategyPropagator : Identifier, MessagePropagator {

    /**
     * 执行传播操作
     *
     * 将命令处理结果传播到指定的等待端点
     *
     * @param commandWaitEndpoint 命令等待端点地址
     * @param header 消息头信息，包含元数据和上下文信息
     */
    fun propagate(commandWaitEndpoint: String, header: Header)

    override fun propagate(header: Header, upstream: Message<*, *>) {
        if (upstream !is CommandMessage<*>) {
            return
        }
        val commandWaitEndpoint = upstream.header.extractCommandWaitEndpoint() ?: return
        propagate(commandWaitEndpoint, header)
    }
}

/**
 * Command Wait Strategy
 * @see me.ahoo.wow.command.wait.stage.WaitingForStage
 */
interface WaitStrategy : WaitStrategyPropagator, CompletedCapable {
    val cancelled: Boolean
    val terminated: Boolean
    override val completed: Boolean
        get() = terminated || cancelled
    val materialized: Materialized
    override val id: String

    /**
     * 是否支持虚空命令
     */
    val supportVoidCommand: Boolean
    fun waiting(): Flux<WaitSignal>
    fun waitingLast(): Mono<WaitSignal> {
        return waiting().last()
    }

    fun error(throwable: Throwable)

    /**
     * 由下游(CommandBus or Aggregate or Projector)发送处理结果信号.
     */
    fun next(signal: WaitSignal)

    fun complete()

    fun onFinally(doFinally: Consumer<SignalType>)

    override fun propagate(commandWaitEndpoint: String, header: Header) {
        materialized.propagate(commandWaitEndpoint, header)
    }

    override fun propagate(header: Header, upstream: Message<*, *>) {
        materialized.propagate(header, upstream)
    }

    interface Materialized :
        Identifier,
        WaitStrategyPropagator,
        ProcessingStageShouldNotifyPredicate,
        WaitSignalShouldNotifyPredicate,
        me.ahoo.wow.api.naming.Materialized
}
