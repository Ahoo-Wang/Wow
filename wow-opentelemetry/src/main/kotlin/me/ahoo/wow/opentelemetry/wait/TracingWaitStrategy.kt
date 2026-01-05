package me.ahoo.wow.opentelemetry.wait

import io.opentelemetry.context.Context
import me.ahoo.wow.command.wait.WaitSignal
import me.ahoo.wow.command.wait.WaitStrategy
import me.ahoo.wow.opentelemetry.TraceFlux
import me.ahoo.wow.opentelemetry.TraceMono
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class TracingWaitStrategy(
    private val delegate: WaitStrategy,
) : WaitStrategy by delegate {

    override fun waiting(): Flux<WaitSignal> {
        return TraceFlux(
            parentContext = Context.current(),
            instrumenter = WaitStrategyInstrumenter.INSTRUMENTER,
            request = "waiting",
            source = delegate.waiting()
        )
    }

    override fun waitingLast(): Mono<WaitSignal> {
        return TraceMono(
            parentContext = Context.current(),
            instrumenter = WaitStrategyInstrumenter.INSTRUMENTER,
            request = "waitingLast",
            source = delegate.waitingLast()
        )
    }
}
