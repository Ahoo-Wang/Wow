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

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.api.naming.Materialized
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.function.Consumer

/**
 * Command Wait Strategy
 * @see me.ahoo.wow.command.wait.stage.WaitingForStage
 */
interface WaitStrategy {
    val cancelled: Boolean
    val terminated: Boolean

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

    fun inject(commandWaitEndpoint: CommandWaitEndpoint, header: Header)

    interface Info :
        CommandWaitEndpoint,
        ProcessingStageShouldNotifyPredicate,
        WaitSignalShouldNotifyPredicate,
        Materialized
}
