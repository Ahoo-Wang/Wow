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

package me.ahoo.wow.opentelemetry.aggregate

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.instrumenter.Instrumenter
import io.opentelemetry.instrumentation.api.instrumenter.SpanNameExtractor
import me.ahoo.wow.command.ServerCommandExchange
import me.ahoo.wow.opentelemetry.WowInstrumenter
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_VERSION
import me.ahoo.wow.opentelemetry.messaging.MessageExchangeTextMapGetter

object AggregateInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}aggregate"
    val INSTRUMENTER: Instrumenter<ServerCommandExchange<Any>, Unit> =
        Instrumenter.builder<ServerCommandExchange<Any>, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            AggregateSpanNameExtractor,
        ).addAttributesExtractor(AggregateAttributesExtractor)
            .setInstrumentationVersion(INSTRUMENTATION_VERSION)
            .buildConsumerInstrumenter(MessageExchangeTextMapGetter())
}

object AggregateSpanNameExtractor : SpanNameExtractor<ServerCommandExchange<Any>> {
    override fun extract(request: ServerCommandExchange<Any>): String {
        return "${request.message.aggregateName}.${request.message.name}"
    }
}

object AggregateAttributesExtractor : AttributesExtractor<ServerCommandExchange<Any>, Unit> {

    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: ServerCommandExchange<Any>) {
        WowInstrumenter.appendMessageIdAttributes(attributes, request.message)
        WowInstrumenter.appendRequestIdAttributes(attributes, request.message)
        WowInstrumenter.appendAggregateAttributes(attributes, request.message.aggregateId)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: ServerCommandExchange<Any>,
        response: Unit?,
        error: Throwable?
    ) = Unit
}
