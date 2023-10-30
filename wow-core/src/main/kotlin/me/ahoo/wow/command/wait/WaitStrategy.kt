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

import me.ahoo.wow.messaging.processor.ProcessorInfo
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.util.*

/**
 * Command Wait Strategy
 * @see WaitingFor
 */
interface WaitStrategy : ProcessorInfo {
    fun waiting(): Mono<WaitSignal>

    fun error(throwable: Throwable)

    /**
     * 由下游(CommandBus or Aggregate or Projector)发送处理结果信号.
     */
    fun next(signal: WaitSignal)
}

class WaitingFor(
    val stage: CommandStage,
    override val contextName: String,
    override val processorName: String = ""
) : WaitStrategy {

    companion object {
        private val log = LoggerFactory.getLogger(WaitingFor::class.java)
        fun processed(contextName: String): WaitingFor =
            stage(stage = CommandStage.PROCESSED, contextName = contextName)

        fun snapshot(contextName: String): WaitingFor =
            stage(stage = CommandStage.SNAPSHOT, contextName = contextName)

        fun projected(contextName: String, processorName: String = ""): WaitingFor =
            stage(stage = CommandStage.PROJECTED, contextName = contextName, processorName = processorName)

        fun eventHandled(contextName: String, processorName: String = ""): WaitingFor =
            stage(stage = CommandStage.EVENT_HANDLED, contextName = contextName, processorName = processorName)

        fun sagaHandled(contextName: String, processorName: String = ""): WaitingFor =
            stage(stage = CommandStage.SAGA_HANDLED, contextName = contextName, processorName = processorName)

        fun stage(stage: CommandStage, contextName: String, processorName: String = ""): WaitingFor =
            WaitingFor(stage = stage, contextName = contextName, processorName = processorName)

        fun stage(stage: String, contextName: String, processorName: String = ""): WaitingFor =
            stage(
                stage = CommandStage.valueOf(stage.uppercase(Locale.getDefault())),
                contextName = contextName,
                processorName = processorName
            )
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
        if (!signal.succeeded && stage.isAfter(signal.stage)) {
            // fail fast
            sink.tryEmitValue(signal)
            return
        }
        if (stage != signal.stage) {
            return
        }

        if (stage == CommandStage.SENT || stage == CommandStage.PROCESSED || stage == CommandStage.SNAPSHOT) {
            sink.tryEmitValue(signal)
            return
        }

        if (!isSameBoundedContext(signal)) {
            return
        }
        if (processorName.isBlank()) {
            if (signal.isLastProjection) {
                sink.tryEmitValue(signal)
            }
            return
        }

        if (processorName == signal.processorName) {
            sink.tryEmitValue(signal)
            return
        }
    }

    override fun toString(): String {
        return "WaitingFor(stage=$stage, contextName='$contextName', processorName='$processorName')"
    }
}
