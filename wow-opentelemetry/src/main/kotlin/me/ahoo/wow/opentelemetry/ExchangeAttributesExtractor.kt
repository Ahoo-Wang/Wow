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

package me.ahoo.wow.opentelemetry

import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.messaging.handler.MessageExchange
import me.ahoo.wow.opentelemetry.WowInstrumenter.appendMessageAttributes

class ExchangeAttributesExtractor<M, E : MessageExchange<*, M>> :
    AttributesExtractor<E, Unit> where M : Message<*, *> {
    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: E) {
        attributes.appendMessageAttributes(request.message)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: E,
        response: Unit?,
        error: Throwable?
    ) = Unit
}
