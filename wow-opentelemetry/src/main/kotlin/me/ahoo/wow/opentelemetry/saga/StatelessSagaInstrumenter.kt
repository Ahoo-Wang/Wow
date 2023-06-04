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

package me.ahoo.wow.opentelemetry.saga

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import me.ahoo.wow.event.DomainEventExchange
import me.ahoo.wow.opentelemetry.WowInstrumenter
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX
import me.ahoo.wow.opentelemetry.eventprocessor.EventProcessorAttributesExtractor
import me.ahoo.wow.opentelemetry.eventprocessor.EventProcessorSpanNameExtractor
import me.ahoo.wow.opentelemetry.messaging.MessageExchangeTextMapGetter

object StatelessSagaInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}statelessSaga"
    val INSTRUMENTER: Instrumenter<DomainEventExchange<Any>, Unit> =
        Instrumenter.builder<DomainEventExchange<Any>, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            EventProcessorSpanNameExtractor,
        ).addAttributesExtractor(EventProcessorAttributesExtractor)
            .setInstrumentationVersion(WowInstrumenter.INSTRUMENTATION_VERSION)
            .buildConsumerInstrumenter(MessageExchangeTextMapGetter())
}
