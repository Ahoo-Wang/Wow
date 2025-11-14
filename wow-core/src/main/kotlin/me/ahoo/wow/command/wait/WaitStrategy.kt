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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.messaging.function.NamedFunctionInfoData
import me.ahoo.wow.api.messaging.function.NullableFunctionInfoCapable
import me.ahoo.wow.api.naming.CompletedCapable
import me.ahoo.wow.messaging.propagation.MessagePropagator
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.function.Consumer

/**
 * Interface for propagating wait strategy information.
 *
 * Defines methods for controlling how command processing results are
 * propagated to waiting endpoints in distributed scenarios.
 *
 * @see WaitStrategy
 */
interface WaitStrategyPropagator {
    /**
     * Executes propagation operation.
     *
     * Propagates command processing results to the specified wait endpoint.
     *
     * @param commandWaitEndpoint the command wait endpoint address
     * @param header message header containing metadata and context
     */
    fun propagate(
        commandWaitEndpoint: String,
        header: Header
    )
}

/**
 * Command Wait Strategy
 * @see me.ahoo.wow.command.wait.stage.WaitingForStage
 */

/**
 * Command Wait Strategy
 * @see me.ahoo.wow.command.wait.stage.WaitingForStage
 */
interface WaitStrategy :
    WaitCommandIdCapable,
    WaitStrategyPropagator,
    CompletedCapable {
    val cancelled: Boolean
    val terminated: Boolean
    override val completed: Boolean
        get() = terminated || cancelled
    val materialized: Materialized

    /**
     * Whether this strategy supports void commands.
     */
    val supportVoidCommand: Boolean

    /**
     * Returns a flux of wait signals as processing progresses.
     *
     * @return a Flux emitting WaitSignal objects for each processing stage
     */
    fun waiting(): Flux<WaitSignal>

    /**
     * Returns a mono that completes with the final wait signal.
     *
     * @return a Mono emitting the final WaitSignal when processing is complete
     */
    fun waitingLast(): Mono<WaitSignal>

    /**
     * Signals an error occurred during command processing.
     *
     * @param throwable the error that occurred
     */
    fun error(throwable: Throwable)

    /**
     * Receives the next processing result signal from downstream processors.
     *
     * Called by downstream components (CommandBus, Aggregate, Projector) to
     * send processing result signals.
     *
     * @param signal the wait signal from downstream processing
     */
    fun next(signal: WaitSignal)

    /**
     * Marks the wait strategy as completed.
     */
    fun complete()

    /**
     * Registers a callback to be executed when the strategy completes.
     *
     * @param doFinally callback to execute on completion with signal type
     */
    fun onFinally(doFinally: Consumer<SignalType>)

    /**
     * 执行传播操作
     *
     * 将命令处理结果传播到指定的等待端点
     *
     * @param commandWaitEndpoint 命令等待端点地址
     * @param header 消息头信息，包含元数据和上下文信息
     */
    override fun propagate(
        commandWaitEndpoint: String,
        header: Header
    ) {
        header.propagateWaitCommandId(waitCommandId)
        materialized.propagate(commandWaitEndpoint, header)
    }

    interface Materialized :
        ProcessingStageShouldNotifyPredicate,
        WaitSignalShouldNotifyPredicate,
        me.ahoo.wow.api.naming.Materialized,
        WaitStrategyPropagator,
        MessagePropagator {
        /**
         * 判断是否应该传播指定的消息
         *
         * @param upstream 上游消息对象，包含命令或事件的相关信息
         * @return 如果应该传播该消息则返回 true，否则返回 false
         */
        fun shouldPropagate(upstream: Message<*, *>): Boolean = upstream is CommandMessage<*>

        override fun propagate(
            header: Header,
            upstream: Message<*, *>
        ) {
            val commandWaitEndpoint = upstream.header.requireExtractCommandWaitEndpoint()
            propagate(commandWaitEndpoint, header)
        }
    }

    interface FunctionMaterialized :
        Materialized,
        CommandStageCapable,
        NullableFunctionInfoCapable<NamedFunctionInfoData> {
        override fun shouldNotify(processingStage: CommandStage): Boolean = stage.shouldNotify(processingStage)

        override fun shouldNotify(signal: WaitSignal): Boolean {
            if (stage.isPrevious(signal.stage)) {
                return true
            }
            if (stage != signal.stage) {
                return false
            }
            if (!stage.shouldWaitFunction) {
                return true
            }
            return this.function.isWaitingForFunction(signal.function)
        }
    }
}
