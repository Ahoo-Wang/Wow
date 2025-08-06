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

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.Scannable
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer

abstract class WaitingFor : WaitStrategy {
    companion object {
        val DEFAULT_BUSY_LOOPING_DURATION: Duration = Duration.ofMillis(10)
        private val log = KotlinLogging.logger {}
    }

    protected val waitSignalSink: Sinks.Many<WaitSignal> = Sinks.many().unicast().onBackpressureBuffer()
    override val cancelled: Boolean
        get() = Scannable.from(waitSignalSink).scanOrDefault(Scannable.Attr.CANCELLED, false)

    override val terminated: Boolean
        get() = Scannable.from(waitSignalSink).scanOrDefault(Scannable.Attr.TERMINATED, false)

    override val supportVoidCommand: Boolean = false

    protected var onFinallyHook: AtomicReference<Consumer<SignalType>> = AtomicReference(EmptyOnFinally)

    @Suppress("TooGenericExceptionCaught")
    protected fun safeDoFinally(signalType: SignalType) {
        val currentHook = onFinallyHook.get()
        try {
            currentHook.accept(signalType)
        } catch (error: Throwable) {
            log.error(error) {
                "Finally hook execution failed"
            }
        }
    }

    override fun waiting(): Flux<WaitSignal> {
        return waitSignalSink.asFlux().doFinally(this::safeDoFinally)
    }

    protected fun busyLooping(): Sinks.EmitFailureHandler {
        return Sinks.EmitFailureHandler.busyLooping(DEFAULT_BUSY_LOOPING_DURATION)
    }

    protected fun tryEmit(emit: () -> Unit): Boolean {
        if (completed) {
            log.warn {
                "WaitingFor is terminated or cancelled, ignore emit."
            }
            return false
        }
        emit()
        return true
    }

    /**
     * 判断给定的等待信号是否为前置信号
     *
     * @param signal 等待信号
     * @return 如果是前置信号则返回 true，否则返回 false
     */
    abstract fun isPreviousSignal(signal: WaitSignal): Boolean

    protected open fun nextSignal(signal: WaitSignal) {
        tryEmit {
            waitSignalSink.emitNext(signal, busyLooping())
            /**
             * fail fast
             */
            if (signal.succeeded.not() && isPreviousSignal(signal)) {
                complete()
            }
        }
    }

    override fun error(throwable: Throwable) {
        tryEmit {
            waitSignalSink.emitError(throwable, busyLooping())
        }
    }

    override fun complete() {
        tryEmit {
            waitSignalSink.emitComplete(busyLooping())
        }
    }

    override fun onFinally(doFinally: Consumer<SignalType>) {
        check(this.onFinallyHook.compareAndSet(EmptyOnFinally, doFinally)) {
            "Finally hook already set [${this.onFinallyHook.get()}]"
        }
    }

    object EmptyOnFinally : Consumer<SignalType> {
        override fun accept(t: SignalType) = Unit
    }
}
