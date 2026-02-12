package me.ahoo.wow.opentelemetry.wait

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object WaitStrategyInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}wait"

    val INSTRUMENTER: Instrumenter<CommandMessage<*>, Unit> =
        Instrumenter.builder<CommandMessage<*>, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            WaitStrategySpaceNameExtractor,
        ).setInstrumentationVersion(Wow.VERSION)
            .buildInstrumenter()
}

object WaitStrategySpaceNameExtractor : SpanNameExtractor<CommandMessage<*>> {
    override fun extract(request: CommandMessage<*>): String {
        return "${request.aggregateName}.${request.name}.waiting"
    }
}
