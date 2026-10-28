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

package me.ahoo.wow.spi.json

import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.result.CommandResult
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.result.Stage
import me.ahoo.wow.api.schedule.JobScope
import me.ahoo.wow.api.schedule.Misfire
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.spi.ledger.LedgerRequest
import me.ahoo.wow.spi.ledger.Recoverable
import me.ahoo.wow.spi.transport.EnvelopeKind
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * Wow's own enumerations on the wire, each listed here and written by its wire name ([WireNames]):
 * `CATCH_UP` is `catch-up`. A reader takes the wire name alone; a value it does not know fails, unless the reader may be
 * older than its writer ([module] with `tolerant`), which reads it as the enumeration's `UNKNOWN` where it has one.
 *
 * Only the enumerations of this list are respelled: a domain's enumerations in its commands, events and states are
 * written as its Jackson writes them, so no global naming strategy is set.
 */
object WowEnums {
    /** The enumerations written by their wire names. */
    val TYPES: List<Class<out Enum<*>>> = listOf(
        CommandResult.Status::class.java,
        Stage::class.java,
        FunctionKind::class.java,
        Creation::class.java,
        Ownership::class.java,
        JobScope::class.java,
        Misfire::class.java,
        EnvelopeKind::class.java,
        Recoverable::class.java,
        LedgerRequest::class.java,
    )

    /** The name of [module]'s Jackson module, a reader's or a writer's. */
    const val MODULE_NAME: String = "wow-enums"

    /** The Jackson module of [TYPES]; one that is [tolerant] reads an unknown value as `UNKNOWN` where there is one. */
    fun module(tolerant: Boolean): JacksonModule = SimpleModule(MODULE_NAME).apply {
        TYPES.forEach { type -> register(type, tolerant) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <E : Enum<E>> SimpleModule.register(type: Class<out Enum<*>>, tolerant: Boolean) {
        val enumType = type as Class<E>
        addSerializer(enumType, Writer())
        addDeserializer(enumType, Reader(enumType, tolerant))
    }

    /** Writes a value by its wire name. */
    private class Writer<E : Enum<E>> : ValueSerializer<E>() {
        override fun serialize(value: E, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeString(WireNames.of(value))
        }
    }

    /** Reads a value by its wire name; an unknown one is `UNKNOWN` when [tolerant] and the type has one, else fails. */
    private class Reader<E : Enum<E>>(
        private val type: Class<E>,
        private val tolerant: Boolean
    ) : ValueDeserializer<E>() {
        private val byWire: Map<String, E> = type.enumConstants.associateBy { WireNames.of(it) }
        private val unknown: E? = type.enumConstants.firstOrNull { it.name == UNKNOWN }?.takeIf { tolerant }

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): E {
            val text = p.valueAsString
            return byWire[text] ?: unknown ?: ctxt.handleWeirdStringValue(
                type,
                text,
                "not one of ${byWire.keys.filter { it != WireNames.of(UNKNOWN) }}",
            ) as E
        }
    }

    private const val UNKNOWN = "UNKNOWN"
}
