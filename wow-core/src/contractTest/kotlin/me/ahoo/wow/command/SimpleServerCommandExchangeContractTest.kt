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

package me.ahoo.wow.command

import me.ahoo.test.asserts.assert
import me.ahoo.wow.id.generateGlobalId
import me.ahoo.wow.messaging.handler.COMMAND_RESULT_KEY
import me.ahoo.wow.messaging.handler.ERROR_KEY
import me.ahoo.wow.messaging.handler.FUNCTION_KEY
import me.ahoo.wow.messaging.handler.SERVICE_PROVIDER_KEY
import me.ahoo.wow.modeling.command.COMMAND_AGGREGATE_KEY
import me.ahoo.wow.tck.mock.MockVoidCommand
import org.junit.jupiter.api.Test

class SimpleServerCommandExchangeContractTest {

    @Test
    fun `known attributes are stored outside the attributes map`() {
        val exchange = SimpleServerCommandExchange(message())
        val knownAttributes = knownAttributes()

        knownAttributes.forEach { (key, value) ->
            exchange.setAttribute(key, value).assert().isSameAs(exchange)
            exchange.getAttribute<AttributeValue>(key).assert().isSameAs(value)
        }

        exchange.attributes.assert().isEmpty()
    }

    @Test
    fun `known attributes are removed from dedicated fields`() {
        val exchange = SimpleServerCommandExchange(message())
        val knownAttributes = knownAttributes()
        knownAttributes.forEach { (key, value) ->
            exchange.setAttribute(key, value)
        }

        knownAttributes.keys.forEach { key ->
            exchange.removeAttribute(key).assert().isSameAs(exchange)
            exchange.getAttribute<AttributeValue>(key).assert().isNull()
        }

        exchange.attributes.assert().isEmpty()
    }

    @Test
    fun `unknown attributes still use the attributes map`() {
        val exchange = SimpleServerCommandExchange(message())
        val value = AttributeValue("custom")

        exchange.setAttribute(UNKNOWN_KEY, value).assert().isSameAs(exchange)
        exchange.getAttribute<AttributeValue>(UNKNOWN_KEY).assert().isSameAs(value)
        exchange.attributes[UNKNOWN_KEY].assert().isSameAs(value)

        exchange.removeAttribute(UNKNOWN_KEY).assert().isSameAs(exchange)
        exchange.getAttribute<AttributeValue>(UNKNOWN_KEY).assert().isNull()
        exchange.attributes.containsKey(UNKNOWN_KEY).assert().isFalse()
    }

    @Test
    fun `constructor migrates known attributes and keeps unknown attributes`() {
        val knownValue = AttributeValue("event-stream")
        val customValue = AttributeValue("custom")
        val attributes = linkedMapOf<String, Any>(
            EVENT_STREAM_KEY to knownValue,
            UNKNOWN_KEY to customValue,
        )

        val exchange = SimpleServerCommandExchange(message(), attributes)

        exchange.getAttribute<AttributeValue>(EVENT_STREAM_KEY).assert().isSameAs(knownValue)
        attributes.containsKey(EVENT_STREAM_KEY).assert().isFalse()
        exchange.getAttribute<AttributeValue>(UNKNOWN_KEY).assert().isSameAs(customValue)
        attributes[UNKNOWN_KEY].assert().isSameAs(customValue)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `constructor keeps null known attributes in the attributes map`() {
        val customValue = AttributeValue("custom")
        val attributes = linkedMapOf<String, Any?>(
            EVENT_STREAM_KEY to null,
            UNKNOWN_KEY to customValue,
        ) as MutableMap<String, Any>

        val exchange = SimpleServerCommandExchange(message(), attributes)

        exchange.getAttribute<AttributeValue>(EVENT_STREAM_KEY).assert().isNull()
        attributes.containsKey(EVENT_STREAM_KEY).assert().isTrue()
        exchange.getAttribute<AttributeValue>(UNKNOWN_KEY).assert().isSameAs(customValue)
    }

    private fun knownAttributes(): Map<String, AttributeValue> =
        linkedMapOf(
            AGGREGATE_METADATA_KEY to AttributeValue("aggregate-metadata"),
            AGGREGATE_PROCESSOR_KEY to AttributeValue("aggregate-processor"),
            COMMAND_INVOKE_RESULT_KEY to AttributeValue("command-invoke-result"),
            EVENT_STREAM_KEY to AttributeValue("event-stream"),
            AGGREGATE_VERSION_KEY to AttributeValue("aggregate-version"),
            COMMAND_AGGREGATE_KEY to AttributeValue("command-aggregate"),
            FUNCTION_KEY to AttributeValue("function"),
            SERVICE_PROVIDER_KEY to AttributeValue("service-provider"),
            ERROR_KEY to AttributeValue("error"),
            COMMAND_RESULT_KEY to AttributeValue("command-result"),
        )

    private fun message() = MockVoidCommand(generateGlobalId()).toCommandMessage()

    private data class AttributeValue(val name: String)

    private companion object {
        const val UNKNOWN_KEY = "custom-key"
    }
}
