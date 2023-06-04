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
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.event.EventStreamExchange
import me.ahoo.wow.opentelemetry.WowInstrumenter
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX
import me.ahoo.wow.opentelemetry.messaging.MessageAttributesExtractor
import me.ahoo.wow.opentelemetry.messaging.MessageExchangeAttributesExtractor
import me.ahoo.wow.opentelemetry.messaging.MessageExchangeTextMapGetter
import me.ahoo.wow.opentelemetry.messaging.MessageTextMapSetter

object EventConsumerInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}eventConsumer"
    val INSTRUMENTER: Instrumenter<EventStreamExchange, Unit> =
        Instrumenter.builder<EventStreamExchange, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            EventConsumerSpanNameExtractor,
        ).addAttributesExtractor(MessageExchangeAttributesExtractor())
            .setInstrumentationVersion(WowInstrumenter.INSTRUMENTATION_VERSION)
            .buildConsumerInstrumenter(MessageExchangeTextMapGetter())
}

object EventConsumerSpanNameExtractor : SpanNameExtractor<EventStreamExchange> {
    override fun extract(request: EventStreamExchange): String {
        return "${request.message.aggregateName}.event process"
    }
}
