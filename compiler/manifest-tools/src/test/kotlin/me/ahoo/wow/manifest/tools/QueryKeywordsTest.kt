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

package me.ahoo.wow.manifest.tools

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.Mask
import me.ahoo.wow.api.query.annotation.MaskStrategy
import me.ahoo.wow.api.query.annotation.QueryAlias
import me.ahoo.wow.api.query.annotation.QueryDecimal
import me.ahoo.wow.api.query.annotation.QueryDuration
import me.ahoo.wow.api.query.annotation.QueryMoney
import me.ahoo.wow.api.query.annotation.QueryReference
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The property keywords of the query field annotations ([QueryKeywords]) and the checks behind them. */
class QueryKeywordsTest {
    @JvmInline
    @Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 3, keepSuffix = 4))
    value class PhoneNumber(val value: String)

    object Redact : MaskStrategy {
        override fun mask(value: String): String = "***"
    }

    /** An annotation class that declares a sensitivity wherever it is used. */
    @Target(AnnotationTarget.FIELD)
    @Retention(AnnotationRetention.RUNTIME)
    @Sensitive(SensitivityLevel.CONFIDENTIAL)
    annotation class Secret

    data class Address(
        val city: String,
        @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val street: String,
    )

    data class Line(val sku: String, @field:QueryDecimal(scale = 3) val quantity: BigDecimal)

    /** One property per keyword, and the places a keyword can sit: nested, in array items, by `$ref`, nullable. */
    data class OrderState(
        @field:QueryTemporal(unit = TimeUnit.SECONDS)
        val createdAt: Long,
        @field:QueryTemporal(pattern = "yyyy-MM-dd HH:mm:ss")
        val shippedAt: String?,
        val placedAt: Instant,
        @field:QueryDecimal(scale = 4)
        val rate: BigDecimal,
        @field:QueryMoney(currency = "CNY")
        val total: BigDecimal,
        @field:QueryMoney(currency = "JPY", scale = 1)
        val tip: BigDecimal,
        @field:QueryMoney(currencyField = "currency", scale = 2)
        val paid: BigDecimal,
        val currency: String,
        @field:QueryDuration(TimeUnit.SECONDS)
        val timeout: Int,
        @field:QueryReference("member")
        val buyerId: String,
        @field:QueryReference("product", contextName = "catalog")
        val productIds: List<String>,
        @field:QueryAlias("state.customer", "state.client")
        @JsonProperty("buyer")
        val buyerName: String,
        @get:QueryAlias("state.remark")
        val note: String,
        @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val password: String,
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = Redact::class))
        val email: String?,
        @field:Secret
        val token: String,
        val phone: PhoneNumber,
        @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val backupPhone: PhoneNumber,
        val address: Address,
        @field:QueryAlias("state.deliverTo")
        val shipping: Address?,
        val lines: List<Line>,
    )

    private val mapper = jacksonObjectMapper()

    @Test
    fun `the state schema should carry each query keyword`() {
        val snapshot = javaClass.getResourceAsStream("/query-keywords/order-state.defs.json")!!.use {
            mapper.readValue(it, Map::class.java)
        }
        complete(OrderState::class.java).defs.assert().isEqualTo(snapshot)
    }

    enum class ItemType { A, B }

    /** The item of #5166: an epoch-millisecond time, non-null and nullable, beside an enum. */
    data class Item(
        val type: ItemType,
        @get:QueryTemporal(unit = TimeUnit.MILLISECONDS)
        val startDate: Long,
        @get:QueryTemporal(unit = TimeUnit.MILLISECONDS)
        val endDate: Long?,
    )

    data class ItemListState(val items: List<Item>)

    data class ItemMapState(val itemsById: Map<String, Item>)

    data class ItemListAndMapState(val items: List<Item>, val itemsById: Map<String, Item>)

    data class TwoItemListsState(val first: List<Item>, val second: List<Item>)

    @Test
    fun `a temporal keyword should stay on its property in list elements and map values alike`() {
        // Each state in a Manifest of its own: an item used once in a list is inlined, a map value or an item used
        // twice is a $ref to its definition.
        val defs = listOf(
            ItemListState::class.java,
            ItemMapState::class.java,
            ItemListAndMapState::class.java,
            TwoItemListsState::class.java,
        ).associate { it.name to complete(it).defs }
        val snapshot = javaClass.getResourceAsStream("/query-keywords/item-states.defs.json")!!.use {
            mapper.readValue(it, Map::class.java)
        }
        defs.assert().isEqualTo(snapshot)
        defs.forEach { (state, stateDefs) ->
            ((stateDefs.getValue(state) as Map<*, *>)["properties"] as Map<*, *>).values.forEach { property ->
                property as Map<*, *>
                val value = (property["items"] ?: property["additionalProperties"]) as Map<*, *>
                val item = ((value["\$ref"] as? String)?.let { stateDefs[Manifest.schemaKey(it)] } ?: value)
                    .let { (it as Map<*, *>)["properties"] as Map<*, *> }
                // The keyword sits on the nullable property itself, never on a branch of it.
                item["endDate"].assert().isEqualTo(
                    mapOf(
                        "type" to listOf("integer", "null"),
                        "format" to "int64",
                        "x-wow-temporal" to mapOf("unit" to "MILLISECONDS"),
                    ),
                )
                item["startDate"].assert().isEqualTo(
                    mapOf(
                        "type" to "integer",
                        "format" to "int64",
                        "x-wow-temporal" to mapOf("unit" to "MILLISECONDS"),
                    ),
                )
            }
        }
    }

    /** Semantic types declared on maps, which their values carry: an epoch time and a duration, nullable or not. */
    data class SemanticMapsState(
        @get:QueryTemporal(unit = TimeUnit.MILLISECONDS)
        val deadlines: Map<String, Long>,
        @get:QueryTemporal(unit = TimeUnit.MILLISECONDS)
        val reminders: Map<String, Long?>,
        @get:QueryDuration(TimeUnit.SECONDS)
        val timeouts: Map<String, Long>,
        @get:QueryDuration(TimeUnit.SECONDS)
        val pauses: Map<String, Long?>,
    )

    @Test
    fun `a semantic keyword on a map should sit on the map`() {
        val defs = complete(SemanticMapsState::class.java).defs
        val snapshot = javaClass.getResourceAsStream("/query-keywords/semantic-maps.defs.json")!!.use {
            mapper.readValue(it, Map::class.java)
        }
        defs.assert().isEqualTo(snapshot)
        val properties = (defs.getValue(SemanticMapsState::class.java.name) as Map<*, *>)["properties"] as Map<*, *>
        val temporal = "x-wow-temporal" to mapOf("unit" to "MILLISECONDS")
        val duration = "x-wow-duration" to mapOf("unit" to "SECONDS")
        val long = mapOf("type" to "integer", "format" to "int64")
        val nullableLong = mapOf("type" to listOf("integer", "null"), "format" to "int64")
        mapOf(
            "deadlines" to (temporal to long),
            "reminders" to (temporal to nullableLong),
            "timeouts" to (duration to long),
            "pauses" to (duration to nullableLong),
        ).forEach { (name, expected) ->
            // The keyword sits on the map, written where it is used, whose values are the integers it means.
            val (keyword, values) = expected
            properties[name].assert().isEqualTo(
                mapOf("type" to "object", "additionalProperties" to values, keyword.first to keyword.second),
            )
        }
    }

    data class ScheduleRulesState(val at: ScheduleRule.At, val after: ScheduleRule.After)

    @Test
    fun `a schedule rule's instant and delay should carry their keywords`() {
        val properties = state(ScheduleRulesState::class.java)["properties"] as Map<*, *>

        fun part(rule: String, name: String) =
            ((properties[rule] as Map<*, *>)["properties"] as Map<*, *>)[name] as Map<*, *>
        part("at", "epochMillis")["x-wow-temporal"].assert().isEqualTo(mapOf("unit" to "MILLISECONDS"))
        part("after", "millis")["x-wow-duration"].assert().isEqualTo(mapOf("unit" to "MILLISECONDS"))
    }

    data class PlaceOrder(@field:QueryAlias("state.customer") val buyer: String)

    @Test
    fun `the keywords should also be written into the schemas of commands and events`() {
        val fragment = fragment(Address::class.java).let { manifest ->
            val context = manifest.contexts.single()
            val aggregate = context.aggregates.single()
                .copyWith(
                    commands = listOf(CommandManifest("PlaceOrder", schema = schemaRef(PlaceOrder::class.java.name)))
                )
            manifest.copyWith(contexts = listOf(context.copyWith(aggregates = listOf(aggregate))))
        }
        val placeOrder = introspector.complete(fragment).defs[PlaceOrder::class.java.name] as Map<*, *>
        (placeOrder["properties"] as Map<*, *>)["buyer"].assert()
            .isEqualTo(mapOf("type" to "string", "x-wow-alias" to listOf("state.customer")))
    }

    data class TwoSemanticTypes(@field:QueryDecimal(2) @field:QueryDuration(TimeUnit.SECONDS) val value: BigDecimal)

    data class TwoMoneys(@field:QueryMoney(currency = "CNY") @field:QueryReference("member") val value: String)

    @Test
    fun `a field should have one semantic type`() {
        failure(TwoSemanticTypes::class.java).assert()
            .isEqualTo(
                "A field has one semantic type: ${TwoSemanticTypes::class.java.name}.value declares @QueryDecimal " +
                    "and @QueryDuration."
            )
        failure(TwoMoneys::class.java).assert().contains("declares @QueryMoney and @QueryReference")
    }

    data class UnitAndPattern(@field:QueryTemporal(unit = TimeUnit.SECONDS, pattern = "yyyy") val year: String)

    @Test
    fun `a temporal field should declare a unit or a pattern, not both`() {
        failure(UnitAndPattern::class.java).assert()
            .isEqualTo(
                "@QueryTemporal on ${UnitAndPattern::class.java.name}.year declares either a unit or a pattern, not both."
            )
    }

    data class TwiceSensitive(
        @field:Secret
        @field:Sensitive(SensitivityLevel.DISPLAY)
        val value: String,
    )

    data class SameSensitivityTwice(
        @field:Secret
        @field:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val value: String,
    )

    @Test
    fun `a field should declare one sensitivity`() {
        failure(TwiceSensitive::class.java).assert()
            .isEqualTo("${TwiceSensitive::class.java.name}.value declares several @Sensitive.")
        sensitive(SameSensitivityTwice::class.java, "value").assert()
            .isEqualTo(mapOf("level" to "confidential"))
    }

    data class SensitiveNumber(@field:Sensitive(SensitivityLevel.DISPLAY) val value: Int)

    @Test
    fun `a sensitive field should be a string`() {
        failure(SensitiveNumber::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${SensitiveNumber::class.java.name}.value: a sensitive field must have the String JVM type."
            )
    }

    @JvmInline
    @Sensitive(SensitivityLevel.CONFIDENTIAL)
    value class IdCard(val value: String)

    data class Loosened(@field:Sensitive(SensitivityLevel.DISPLAY) val idCard: IdCard)

    data class IdCards(val idCards: List<IdCard>, val byName: Map<String, IdCard>)

    @Test
    fun `a field should not loosen its value type's sensitivity`() {
        failure(Loosened::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${Loosened::class.java.name}.idCard loosens its value type ${IdCard::class.java.name} " +
                    "from CONFIDENTIAL to DISPLAY.",
            )
    }

    @Test
    fun `a sensitive value type should protect the collections of it`() {
        // Jackson writes an IdCard element as its string, which introspection cannot see: its schema is declared.
        val overridden = SchemaIntrospector(
            javaClass.classLoader,
            mapOf(IdCard::class.java.name to """{"type":"string"}""")
        )
        val properties = overridden.complete(fragment(IdCards::class.java)).defs[IdCards::class.java.name]
            .let { (it as Map<*, *>)["properties"] as Map<*, *> }
        listOf("idCards", "byName").forEach { property ->
            (properties[property] as Map<*, *>)["x-wow-sensitive"].assert().isEqualTo(mapOf("level" to "confidential"))
        }
    }

    @Sensitive(SensitivityLevel.DISPLAY)
    data class Iban(@JsonValue val value: String)

    data class Account(val iban: Iban)

    @Test
    fun `a type with a JsonValue member should be a sensitive value type`() {
        val overridden = SchemaIntrospector(
            javaClass.classLoader,
            mapOf(Iban::class.java.name to """{"type":"string"}""")
        )
        val properties = overridden.complete(fragment(Account::class.java)).defs[Account::class.java.name]
            .let { (it as Map<*, *>)["properties"] as Map<*, *> }
        (properties["iban"] as Map<*, *>)["x-wow-sensitive"].assert().isEqualTo(mapOf("level" to "display"))
    }

    @Sensitive(SensitivityLevel.DISPLAY)
    data class SensitiveObject(val value: String)

    data class HoldsSensitiveObject(val holder: SensitiveObject)

    @Test
    fun `only a value type should carry a sensitivity`() {
        failure(HoldsSensitiveObject::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${SensitiveObject::class.java.name}: only a value type, a Kotlin value class or a " +
                    "class with a @JsonValue member, may carry it.",
            )
    }

    data class StrategyAndKeep(
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepPrefix = 1, strategy = Redact::class))
        val value: String,
    )

    data class NegativeKeep(@field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(keepSuffix = -1)) val value: String)

    class PrivateStrategy private constructor() : MaskStrategy {
        override fun mask(value: String): String = value
    }

    data class NotInstantiable(
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = PrivateStrategy::class))
        val value: String,
    )

    class PublicStrategy : MaskStrategy {
        override fun mask(value: String): String = value
    }

    data class Instantiable(
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = PublicStrategy::class))
        val value: String,
    )

    @Test
    fun `a mask should be valid`() {
        failure(StrategyAndKeep::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${StrategyAndKeep::class.java.name}.value: a custom mask strategy keeps no prefix or suffix."
            )
        failure(NegativeKeep::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${NegativeKeep::class.java.name}.value: a mask keeps no negative count of code points."
            )
        failure(NotInstantiable::class.java).assert()
            .isEqualTo(
                "@Sensitive on ${NotInstantiable::class.java.name}.value: the mask strategy " +
                    "${PrivateStrategy::class.java.name} must be a Kotlin object or a public class with a public " +
                    "no-argument constructor.",
            )
        val properties = state(Instantiable::class.java)["properties"] as Map<*, *>
        (properties["value"] as Map<*, *>)["x-wow-mask"].assert()
            .isEqualTo(mapOf("strategy" to PublicStrategy::class.java.name))
    }

    abstract class AbstractStrategy : MaskStrategy

    data class AbstractMask(
        @field:Sensitive(SensitivityLevel.DISPLAY, mask = Mask(strategy = AbstractStrategy::class))
        val value: String,
    )

    @Test
    fun `an abstract mask strategy should be refused`() {
        failure(
            AbstractMask::class.java
        ).assert().contains("the mask strategy ${AbstractStrategy::class.java.name} must be")
    }

    @Test
    fun `a Java state should carry its keywords and indexes`() {
        val account = state(JavaAccount::class.java)
        val properties = account["properties"] as Map<*, *>
        (properties["holder"] as Map<*, *>)["x-wow-alias"].assert().isEqualTo(listOf("state.owner"))
        (properties["secret"] as Map<*, *>)["x-wow-sensitive"].assert().isEqualTo(mapOf("level" to "confidential"))
        listOf("codes", "names", "limits").forEach { property ->
            (properties[property] as Map<*, *>).containsKey("x-wow-sensitive").assert().isFalse()
        }
        account["x-wow-index"].assert().isEqualTo(
            listOf(
                mapOf(
                    "name" to "ix_order_state_holder_last_event_time",
                    "keys" to listOf("state.holder", "lastEventTime")
                )
            ),
        )
    }

    private val introspector = SchemaIntrospector(javaClass.classLoader)

    private fun complete(state: Class<*>): Manifest = introspector.complete(fragment(state))

    private fun state(state: Class<*>): Map<*, *> = complete(state).defs[state.name] as Map<*, *>

    private fun sensitive(state: Class<*>, property: String): Any? =
        ((state(state)["properties"] as Map<*, *>)[property] as Map<*, *>)["x-wow-sensitive"]

    private fun failure(state: Class<*>): String? =
        assertThrows<IllegalStateException> { complete(state) }.message

    companion object {
        fun fragment(state: Class<*>, aggregate: String = "order"): Manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(
                        AggregateManifest(name = aggregate, state = StateManifest(schemaRef(state.name)))
                    ),
                ),
            ),
        )
    }
}
