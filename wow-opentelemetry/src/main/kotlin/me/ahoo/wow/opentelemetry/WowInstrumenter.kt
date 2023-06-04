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

import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.common.AttributesBuilder
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.messaging.Message
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.serialization.MessageRecords

object WowInstrumenter {
    private const val INSTRUMENTATION_NAME = "me.ahoo.wow"
    const val INSTRUMENTATION_VERSION = "1.15.2"
    const val INSTRUMENTATION_NAME_PREFIX = "$INSTRUMENTATION_NAME-"

    private const val MESSAGE_PREFIX = Wow.WOW_PREFIX + "message."
    private val MESSAGE_ID_ATTRIBUTE_KEY = stringKey("${MESSAGE_PREFIX}${MessageRecords.ID}")
    private val REQUEST_ID_ATTRIBUTE_KEY = stringKey("${MESSAGE_PREFIX}request_id")
    fun appendMessageIdAttributes(attributesBuilder: AttributesBuilder, message: Message<*, *>) {
        attributesBuilder.put(MESSAGE_ID_ATTRIBUTE_KEY, message.id)
    }

    fun appendRequestIdAttributes(attributesBuilder: AttributesBuilder, message: CommandMessage<*>) {
        attributesBuilder.put(REQUEST_ID_ATTRIBUTE_KEY, message.requestId)
    }

    private const val AGGREGATE_PREFIX = Wow.WOW_PREFIX + "aggregate."
    private val AGGREGATE_CONTEXT_NAME_ATTRIBUTE_KEY = stringKey("${AGGREGATE_PREFIX}context_name")
    private val AGGREGATE_NAME_ATTRIBUTE_KEY =
        stringKey("${AGGREGATE_PREFIX}${MessageRecords.NAME}")
    private val AGGREGATE_ID_ATTRIBUTE_KEY =
        stringKey("${AGGREGATE_PREFIX}${MessageRecords.ID}")
    private val AGGREGATE_TENANT_ID_ATTRIBUTE_KEY =
        stringKey("${AGGREGATE_PREFIX}tenant_id")

    fun appendAggregateAttributes(attributesBuilder: AttributesBuilder, aggregateId: AggregateId) {
        attributesBuilder.put(AGGREGATE_CONTEXT_NAME_ATTRIBUTE_KEY, aggregateId.contextName)
        attributesBuilder.put(AGGREGATE_NAME_ATTRIBUTE_KEY, aggregateId.aggregateName)
        attributesBuilder.put(AGGREGATE_ID_ATTRIBUTE_KEY, aggregateId.id)
        attributesBuilder.put(AGGREGATE_TENANT_ID_ATTRIBUTE_KEY, aggregateId.tenantId)
    }
}
