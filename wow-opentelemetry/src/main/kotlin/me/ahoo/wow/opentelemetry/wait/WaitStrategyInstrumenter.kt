package me.ahoo.wow.opentelemetry.wait

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.api.Wow
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object WaitStrategyInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}wait"

    val INSTRUMENTER: Instrumenter<String, Unit> =
        Instrumenter.builder<String, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            WaitStrategySpaceNameExtractor,
        ).setInstrumentationVersion(Wow.VERSION)
            .buildInstrumenter()
}

object WaitStrategySpaceNameExtractor : SpanNameExtractor<String> {
    override fun extract(request: String): String {
        return "command.${request}"
    }
}
