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

package me.ahoo.wow.opentelemetry.eventprocessor

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.opentelemetry.WowInstrumenter
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object EventProcessorInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}eventProcessor"
    val INSTRUMENTER: Instrumenter<DomainEventExchange<Any>, Unit> =
        Instrumenter.builder<DomainEventExchange<Any>, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            EventProcessorSpanNameExtractor,
        ).addAttributesExtractor(EventProcessorAttributesExtractor)
            .setInstrumentationVersion(WowInstrumenter.INSTRUMENTATION_VERSION)
            .buildInstrumenter()
}

object EventProcessorSpanNameExtractor : SpanNameExtractor<DomainEventExchange<Any>> {
    override fun extract(request: DomainEventExchange<Any>): String {
        val function = checkNotNull(request.eventFunction)
        val processorName = function.processor.javaClass.simpleName
        return "$processorName.${function.supportedType.simpleName}"
    }
}

object EventProcessorAttributesExtractor : AttributesExtractor<DomainEventExchange<Any>, Unit> {

    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: DomainEventExchange<Any>) {
        WowInstrumenter.appendMessageIdAttributes(attributes, request.message)
        WowInstrumenter.appendAggregateAttributes(attributes, request.message.aggregateId)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: DomainEventExchange<Any>,
        response: Unit?,
        error: Throwable?
    ) = Unit
}
