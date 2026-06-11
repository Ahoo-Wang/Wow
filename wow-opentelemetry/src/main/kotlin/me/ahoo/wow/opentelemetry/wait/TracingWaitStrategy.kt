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

class TracingWaitStrategy(
    override val delegate: WaitStrategy,
    private val commandMessage: CommandMessage<*>
) : Traced, WaitStrategy by delegate, Decorator<WaitStrategy> {

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
}

fun WaitStrategy.tracing(commandMessage: CommandMessage<*>): WaitStrategy {
    return tracing {
        return TracingWaitStrategy(this@tracing, commandMessage)
    }
}
