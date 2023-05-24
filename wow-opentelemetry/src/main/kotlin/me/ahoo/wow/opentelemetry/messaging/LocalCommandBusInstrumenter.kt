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
import me.ahoo.wow.opentelemetry.WowInstrumenter
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object LocalCommandBusInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}loadCommandBus"
    val INSTRUMENTER: Instrumenter<ServerCommandExchange<*>, Unit> =
        Instrumenter.builder<ServerCommandExchange<*>, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            LocalCommandBusSpanNameExtractor,
        ).addAttributesExtractor(LocalCommandBusAttributesExtractor)
            .setInstrumentationVersion(WowInstrumenter.INSTRUMENTATION_VERSION)
            .buildInstrumenter()
}

object LocalCommandBusSpanNameExtractor : SpanNameExtractor<ServerCommandExchange<*>> {
    override fun extract(request: ServerCommandExchange<*>): String {
        val commandMessage = request.message as CommandMessage<*>
        return "${commandMessage.aggregateName}.${commandMessage.name}.command send"
    }
}

object LocalCommandBusAttributesExtractor : AttributesExtractor<ServerCommandExchange<*>, Unit> {

    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: ServerCommandExchange<*>) {
        WowInstrumenter.appendMessageIdAttributes(attributes, request.message)
        WowInstrumenter.appendAggregateAttributes(attributes, request.message.aggregateId)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: ServerCommandExchange<*>,
        response: Unit?,
        error: Throwable?
    ) = Unit
}
