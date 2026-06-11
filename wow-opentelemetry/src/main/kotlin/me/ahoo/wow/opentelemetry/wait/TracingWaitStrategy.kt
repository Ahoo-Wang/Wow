package me.ahoo.wow.opentelemetry.wait

import io.opentelemetry.context.Context
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.infra.Decorator
import me.ahoo.wow.opentelemetry.TraceFlux
import me.ahoo.wow.opentelemetry.TraceMono
import me.ahoo.wow.opentelemetry.Traced
import me.ahoo.wow.opentelemetry.Tracing.tracing
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.SignalType
import java.util.function.Consumer

class TracingWaitStrategy(
    override val delegate: WaitStrategy,
    private val commandMessage: CommandMessage<*>,
    private val traceSignals: Boolean = false
) : Traced, WaitStrategy by delegate, Decorator<WaitStrategy> {
    private var signalTraceContext: Context? = null

    override fun waiting(): Flux<WaitSignal> {
        return TraceFlux(
            parentContext = Context.current(),
            instrumenter = WaitStrategyInstrumenter.INSTRUMENTER,
            request = commandMessage,
            source = delegate.waiting()
        )
    }

    override fun waitingLast(): Mono<WaitSignal> {
        return TraceMono(
            parentContext = Context.current(),
            instrumenter = WaitStrategyInstrumenter.INSTRUMENTER,
            request = commandMessage,
            source = delegate.waitingLast()
        )
    }

    @Suppress("TooGenericExceptionCaught")
    override fun next(signal: WaitSignal) {
        startSignalTrace()
        try {
            delegate.next(signal)
        } catch (error: Throwable) {
            endSignalTrace(error)
            throw error
        }
        if (completed) {
            endSignalTrace()
        }
    }

    override fun error(throwable: Throwable) {
        startSignalTrace()
        try {
            delegate.error(throwable)
        } finally {
            endSignalTrace(throwable)
        }
    }

    override fun complete() {
        try {
            delegate.complete()
        } finally {
            if (completed) {
                endSignalTrace()
            }
        }
    }

    override fun onFinally(doFinally: Consumer<SignalType>) {
        if (!traceSignals) {
            delegate.onFinally(doFinally)
            return
        }
        startSignalTrace()
        delegate.onFinally { signalType ->
            try {
                doFinally.accept(signalType)
            } finally {
                endSignalTrace()
            }
        }
    }

    @Synchronized
    private fun startSignalTrace() {
        if (!traceSignals || signalTraceContext != null) {
            return
        }
        val parentContext = Context.current()
        if (!WaitStrategyInstrumenter.INSTRUMENTER.shouldStart(parentContext, commandMessage)) {
            return
        }
        signalTraceContext = WaitStrategyInstrumenter.INSTRUMENTER.start(parentContext, commandMessage)
    }

    @Synchronized
    private fun endSignalTrace(error: Throwable? = null) {
        val otelContext = signalTraceContext ?: return
        signalTraceContext = null
        WaitStrategyInstrumenter.INSTRUMENTER.end(otelContext, commandMessage, null, error)
    }
}

fun WaitStrategy.tracing(
    commandMessage: CommandMessage<*>,
    traceSignals: Boolean = false
): WaitStrategy {
    return tracing {
        return TracingWaitStrategy(this@tracing, commandMessage, traceSignals)
    }
}
