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
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.api.Wow
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.opentelemetry.MessageAttributesExtractor
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object EventProducerInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}eventProducer"
    val INSTRUMENTER: Instrumenter<DomainEventStream, Unit> =
        Instrumenter.builder<DomainEventStream, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            EventProducerSpanNameExtractor,
        ).addAttributesExtractor(MessageAttributesExtractor())
            .setInstrumentationVersion(Wow.VERSION)
            .buildProducerInstrumenter(MessageTextMapSetter())
}

object EventProducerSpanNameExtractor : SpanNameExtractor<DomainEventStream> {
    override fun extract(request: DomainEventStream): String {
        val firstEvent = request.first()
        return "${request.aggregateName}.${firstEvent.name}.event send"
    }
}
