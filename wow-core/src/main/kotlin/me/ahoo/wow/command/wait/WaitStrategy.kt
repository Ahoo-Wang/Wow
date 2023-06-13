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

import me.ahoo.wow.api.naming.NamedBoundedContext
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.*

/**
 * Command Wait Strategy
 * @see WaitingFor
 */
interface WaitStrategy {
    fun waiting(): Mono<WaitSignal>

    fun error(throwable: Throwable)

    /**
     * 由下游(CommandBus or Aggregate or Projector)发送处理结果信号.
     */
    fun next(signal: WaitSignal)
}

class WaitingFor(
    override val contextName: String,
    val stage: CommandStage
) : WaitStrategy, NamedBoundedContext {

    companion object {
        private val log = LoggerFactory.getLogger(WaitingFor::class.java)
        fun processed(contextName: String): WaitingFor = stage(contextName, CommandStage.PROCESSED)
        fun snapshot(contextName: String): WaitingFor = stage(contextName, CommandStage.SNAPSHOT)
        fun projected(contextName: String): WaitingFor = stage(contextName, CommandStage.PROJECTED)
        fun stage(contextName: String, stage: CommandStage): WaitingFor = WaitingFor(contextName, stage)
        fun stage(contextName: String, stage: String): WaitingFor =
            WaitingFor(contextName, CommandStage.valueOf(stage.uppercase(Locale.getDefault())))
    }

    private val sink: Sinks.One<WaitSignal> = Sinks.one()
    override fun waiting(): Mono<WaitSignal> {
        return sink.asMono()
    }

    override fun error(throwable: Throwable) {
        sink.tryEmitError(throwable)
    }

    override fun next(signal: WaitSignal) {
        if (log.isDebugEnabled) {
            log.debug("Next $signal.")
        }
        if (!isSameBoundedContext(signal)) {
            return
        }
        if (!signal.succeeded) {
            // fail fast
            sink.tryEmitValue(signal)
            return
        }
        if (stage == CommandStage.PROJECTED && !signal.isLastProjection) {
            return
        }
        if (stage == signal.stage) {
            sink.tryEmitValue(signal)
            return
        }
    }

    override fun toString(): String {
        return "WaitingFor(stage='$stage')"
    }
}
