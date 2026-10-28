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
import me.ahoo.wow.api.query.AggregationDatePart
import me.ahoo.wow.api.query.AggregationDateUnit
import me.ahoo.wow.api.query.AggregationExpressionOperator
import me.ahoo.wow.api.query.AggregationFunction
import me.ahoo.wow.api.query.AggregationGroupScope
import me.ahoo.wow.api.query.ComparisonOperator
import me.ahoo.wow.api.query.DateDiffUnit
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.FilterOperator
import me.ahoo.wow.api.query.PagingMode
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.RunningFunction
import me.ahoo.wow.api.query.SearchMode
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.StringComparison
import me.ahoo.wow.api.query.TotalMode
import me.ahoo.wow.api.query.WindowFunction
import me.ahoo.wow.api.query.annotation.SensitivityLevel
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
import tools.jackson.databind.KeyDeserializer
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.module.SimpleModule

/**
 * Wow's own enumerations on the wire, each listed here and written by its wire name ([WireNames]):
 * `CATCH_UP` is `catch-up`. A reader takes the wire name alone; a value it does not know fails, unless the reader may be
 * older than its writer ([module] with `tolerant`), which reads it as the enumeration's `UNKNOWN` where it has one.
 *
 * Values and map keys alike are spelled so. Only the enumerations of this list are respelled: a domain's enumerations
 * in its commands, events and states are written as its Jackson writes them, so no global naming strategy is set.
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
        // The query protocol.
        FilterOperator::class.java,
        StringComparison::class.java,
        SearchMode::class.java,
        DeletionState::class.java,
        Sort.Direction::class.java,
        TotalMode::class.java,
        PagingMode::class.java,
        QueryValueKind::class.java,
        AggregationDatePart::class.java,
        AggregationDateUnit::class.java,
        DateDiffUnit::class.java,
        AggregationExpressionOperator::class.java,
        WindowFunction::class.java,
        RunningFunction::class.java,
        ComparisonOperator::class.java,
        AggregationFunction::class.java,
        AggregationGroupScope::class.java,
        SensitivityLevel::class.java,
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
        val names = Names(enumType, tolerant)
        addSerializer(enumType, Writer())
        addDeserializer(enumType, Reader(names))
        addKeySerializer(enumType, KeyWriter())
        addKeyDeserializer(enumType, KeyReader(names))
    }

    /** The constants of [type] by their wire names; an unknown one is `UNKNOWN` when [tolerant] and [type] has one. */
    private class Names<E : Enum<E>>(val type: Class<E>, tolerant: Boolean) {
        private val byWire: Map<String, E> = type.enumConstants.associateBy { WireNames.of(it) }
        private val unknown: E? = type.enumConstants.firstOrNull { it.name == UNKNOWN }?.takeIf { tolerant }

        /** What a value that is none of them is told. */
        val expected: String = "not one of ${byWire.keys.filter { it != WireNames.of(UNKNOWN) }}"

        fun of(wire: String): E? = byWire[wire] ?: unknown
    }

    /** Writes a value by its wire name. */
    private class Writer<E : Enum<E>> : ValueSerializer<E>() {
        override fun serialize(value: E, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeString(WireNames.of(value))
        }
    }

    /** Reads a value by its wire name ([Names]), else fails. */
    private class Reader<E : Enum<E>>(private val names: Names<E>) : ValueDeserializer<E>() {
        @Suppress("UNCHECKED_CAST")
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): E {
            val text = p.valueAsString
            return names.of(text) ?: ctxt.handleWeirdStringValue(names.type, text, names.expected) as E
        }
    }

    /** Writes a map key by its wire name. */
    private class KeyWriter<E : Enum<E>> : ValueSerializer<E>() {
        override fun serialize(value: E, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeName(WireNames.of(value))
        }
    }

    /** Reads a map key by its wire name ([Names]), else fails. */
    private class KeyReader<E : Enum<E>>(private val names: Names<E>) : KeyDeserializer() {
        override fun deserializeKey(key: String, ctxt: DeserializationContext): Any =
            names.of(key) ?: ctxt.handleWeirdKey(names.type, key, names.expected)
    }

    private const val UNKNOWN = "UNKNOWN"
}
