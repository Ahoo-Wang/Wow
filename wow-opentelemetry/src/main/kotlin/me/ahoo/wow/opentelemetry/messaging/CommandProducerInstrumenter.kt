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
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.opentelemetry.MessageAttributesExtractor
import me.ahoo.wow.opentelemetry.WowInstrumenter.INSTRUMENTATION_NAME_PREFIX

object CommandProducerInstrumenter {
    private const val INSTRUMENTATION_NAME = "${INSTRUMENTATION_NAME_PREFIX}commandProducer"
    val INSTRUMENTER: Instrumenter<CommandMessage<*>, Unit> =
        Instrumenter.builder<CommandMessage<*>, Unit>(
            GlobalOpenTelemetry.get(),
            INSTRUMENTATION_NAME,
            CommandProducerSpanNameExtractor,
        ).addAttributesExtractor(MessageAttributesExtractor())
            .setInstrumentationVersion(Wow.VERSION)
            .buildProducerInstrumenter(MessageTextMapSetter())
}

object CommandProducerSpanNameExtractor : SpanNameExtractor<CommandMessage<*>> {
    override fun extract(request: CommandMessage<*>): String {
        return "${request.aggregateName}.${request.name}.command send"
    }
}
