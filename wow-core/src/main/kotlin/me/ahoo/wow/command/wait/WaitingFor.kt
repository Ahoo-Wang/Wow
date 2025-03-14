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

import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.*
import java.util.function.Consumer

interface WaitingFor : WaitStrategy {
    val stage: CommandStage

    companion object {
        fun sent(): WaitingFor = WaitingForSent()
        fun processed(): WaitingFor = WaitingForProcessed()

        fun snapshot(): WaitingFor = WaitingForSnapshot()

        fun projected(contextName: String, processorName: String = ""): WaitingFor =
            WaitingForProjected(
                contextName = contextName,
                processorName = processorName
            )

        fun eventHandled(contextName: String, processorName: String = ""): WaitingFor =
            WaitingForEventHandled(
                contextName = contextName,
                processorName = processorName
            )

        fun sagaHandled(contextName: String, processorName: String = ""): WaitingFor =
            WaitingForSagaHandled(
                contextName = contextName,
                processorName = processorName
            )

        fun stage(stage: CommandStage, contextName: String, processorName: String = ""): WaitingFor {
            return when (stage) {
                CommandStage.SENT -> sent()
                CommandStage.PROCESSED -> processed()
                CommandStage.SNAPSHOT -> snapshot()
                CommandStage.PROJECTED -> projected(contextName, processorName)
                CommandStage.EVENT_HANDLED -> eventHandled(contextName, processorName)
                CommandStage.SAGA_HANDLED -> sagaHandled(contextName, processorName)
            }
        }

        fun stage(stage: String, contextName: String, processorName: String = ""): WaitingFor =
            stage(
                stage = CommandStage.valueOf(stage.uppercase(Locale.getDefault())),
                contextName = contextName,
                processorName = processorName
            )
    }
}

abstract class AbstractWaitingFor : WaitingFor {
    companion object {
        val DEFAULT_BUSY_LOOPING_DURATION: Duration = Duration.ofMillis(10)
    }

    private val waitSignalSink: Sinks.Many<WaitSignal> = Sinks.many().unicast().onBackpressureBuffer()
    override val cancelled: Boolean
        get() = Scannable.from(waitSignalSink).scanOrDefault(Scannable.Attr.CANCELLED, false)

    override val terminated: Boolean
        get() = Scannable.from(waitSignalSink).scanOrDefault(Scannable.Attr.TERMINATED, false)

    private var onFinally: Consumer<SignalType> = EmptyOnFinally
    override fun waiting(): Flux<WaitSignal> {
        return waitSignalSink.asFlux().doFinally {
            onFinally.accept(it)
        }
    }

    private fun busyLooping(): Sinks.EmitFailureHandler {
        return Sinks.EmitFailureHandler.busyLooping(DEFAULT_BUSY_LOOPING_DURATION)
    }

    override fun next(signal: WaitSignal) {
        waitSignalSink.emitNext(signal, busyLooping())
    }

    override fun error(throwable: Throwable) {
        waitSignalSink.emitError(throwable, busyLooping())
    }

    override fun complete() {
        waitSignalSink.emitComplete(busyLooping())
    }

    override fun onFinally(onFinally: Consumer<SignalType>) {
        this.onFinally = onFinally
    }

    object EmptyOnFinally : Consumer<SignalType> {
        override fun accept(t: SignalType) = Unit
    }
}
