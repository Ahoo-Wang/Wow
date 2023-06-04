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

import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateIdCapable
import me.ahoo.wow.opentelemetry.WowInstrumenter

class MessageAttributesExtractor<M> : AttributesExtractor<M, Unit> where M : Message<*, *>, M : AggregateIdCapable {
    override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: M) {
        WowInstrumenter.appendMessageIdAttributes(attributes, request)
        WowInstrumenter.appendAggregateAttributes(attributes, request.aggregateId)
    }

    override fun onEnd(
        attributes: AttributesBuilder,
        context: Context,
        request: M,
        response: Unit?,
        error: Throwable?
    ) = Unit
}
