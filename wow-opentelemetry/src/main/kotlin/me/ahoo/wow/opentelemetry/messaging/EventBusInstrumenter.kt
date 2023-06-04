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

package me.ahoo.wow.opentelemetry.messaging

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.opentelemetry.WowInstrumenter
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object MessageProducerInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}eventProducer"
    val INSTRUMENTER: Instrumenter<EventStreamExchange, Unit> =
        Instrumenter.builder<EventStreamExchange, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            MessageProducerSpanNameExtractor,
        ).addAttributesExtractor(MessageProducerAttributesExtractor)
            .setInstrumentationVersion(WowInstrumenter.INSTRUMENTATION_VERSION)
            .buildProducerInstrumenter(MessageProducerTextMapSetter)
}

object MessageProducerTextMapSetter : TextMapSetter<EventStreamExchange> {
    override fun set(carrier: EventStreamExchange?, key: String, value: String) {
        if (carrier == null) {
            return
        }
        carrier.message.withHeader(mapOf(key to value))
        carrier.attributes[key] = value
    }
}

object MessageProducerSpanNameExtractor : SpanNameExtractor<EventStreamExchange> {
    override fun extract(request: EventStreamExchange): String {
        val firstEvent = request.message.first()
        return "${request.message.aggregateName}.${firstEvent.name}.event send"
    }
}

object MessageProducerAttributesExtractor : AttributesExtractor<EventStreamExchange, Unit> {

    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: EventStreamExchange) {
        WowInstrumenter.appendMessageIdAttributes(attributes, request.message)
        WowInstrumenter.appendAggregateAttributes(attributes, request.message.aggregateId)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: EventStreamExchange,
        response: Unit?,
        error: Throwable?
    ) = Unit
}
