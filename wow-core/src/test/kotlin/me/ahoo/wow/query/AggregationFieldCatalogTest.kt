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

package me.ahoo.wow.query

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fasterxml.jackson.annotation.JsonValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import java.util.UUID

class AggregationFieldCatalogTest {
    @Test
    fun `should scan deep scalar fields and object collections independently`() {
        val catalog = AggregationFieldCatalog.scan(State::class.java)

        catalog.scalarPaths.assert().contains("state.level1.level2.level3.level4.level5.level6.value")
        catalog.elementPaths.assert().contains("state.orders", "state.orders.lines")
        catalog.elementPaths.assert().doesNotContain(
            "state.tags",
            "state.objects",
            "state.scalarItems",
            "state.nestedItems",
            "state.arrayItems",
            "state.mappedItems",
            "state.serializedItems",
            "state.classSerializedItems",
        )
        listOf(
            "state.nestedItems",
            "state.arrayItems",
            "state.mappedItems",
            "state.serializedItems",
            "state.classSerializedItems",
        ).forEach { path ->
            catalog.paths[path]!!.kind.assert().isEqualTo(AggregationFieldKind.UNSUPPORTED_COLLECTION)
        }
        catalog.paths.keys.assert().doesNotContain("state.serializedItems.sku")
        catalog.paths.keys.assert().doesNotContain("state.classSerializedItems.sku")
        catalog.paths.keys.assert().doesNotContain(
            "state.serializedValue",
            "state.serializedValue.sku",
            "state.classSerializedValue",
            "state.classSerializedValue.sku",
            "state.unwrapped",
            "state.unwrapped.sku",
            "state.flat_sku",
            "state.registeredValue",
        )
        catalog.paths.keys.assert().doesNotContain("state.attributes.value")
        catalog.paths["state.scalarItems"]!!.kind.assert().isEqualTo(AggregationFieldKind.SCALAR_COLLECTION)
        catalog.paths.keys.assert().doesNotContain("state.scalarItems.value")
        catalog.paths["state.orders.lines.sku"]!!.collectionPaths.assert()
            .containsExactly("state.orders", "state.orders.lines")
    }

    @Test
    fun `should classify portable scalar collection and polymorphic fields`() {
        val catalog = AggregationFieldCatalog.scan(PortableState::class.java)

        listOf(
            "state.flag",
            "state.character",
            "state.number",
            "state.decimal",
            "state.status",
            "state.uuid",
            "state.date",
            "state.instant",
            "state.localDate",
            "state.localDateTime",
            "state.offsetDateTime",
            "state.zonedDateTime",
            "state.scalarValue",
            "state.numericStatus",
            "state.booleanStatus",
            "state.pet.name",
        ).forEach { path ->
            catalog.paths[path]!!.kind.assert().isEqualTo(AggregationFieldKind.SCALAR)
        }
        catalog.paths["state.numbers"]!!.kind.assert().isEqualTo(AggregationFieldKind.SCALAR_COLLECTION)
        catalog.paths["state.items"]!!.kind.assert().isEqualTo(AggregationFieldKind.OBJECT_COLLECTION)
        catalog.paths["state.items.value"]!!.collectionPaths.assert().containsExactly("state.items")
        catalog.paths["state.number"]!!.isNumeric.assert().isTrue()
        catalog.paths["state.number"]!!.supportsTerms.assert().isTrue()
        catalog.paths["state.scalarValue"]!!.isTextual.assert().isTrue()
        catalog.paths["state.numericStatus"]!!.isNumeric.assert().isTrue()
        catalog.paths["state.booleanStatus"]!!.isBoolean.assert().isTrue()
        catalog.paths["state.instant"]!!.isTemporal.assert().isTrue()
        catalog.paths["state.instant"]!!.supportsTerms.assert().isFalse()
    }

    @Test
    fun `should honor custom depth and cache only the default catalog`() {
        assertThrows<IllegalArgumentException> { AggregationFieldCatalog.scan(State::class.java, 0) }

        val shallow = AggregationFieldCatalog.scan(State::class.java, 3)
        shallow.paths.keys.assert().contains("state.level1.level2")
        shallow.paths.keys.assert().doesNotContain("state.level1.level2.level3")

        val first = AggregationFieldCatalog.scan(State::class.java)
        val second = AggregationFieldCatalog.scan(State::class.java)
        (first === second).assert().isTrue()
    }

    private class State(val id: String) {
        val level1 = Level1()
        val orders: List<Order> = emptyList()
        val tags: List<String> = emptyList()
        val objects: List<Any> = emptyList()
        val scalarItems: List<ScalarItem> = emptyList()
        val nestedItems: List<List<Line>> = emptyList()
        val arrayItems: List<Array<Line>> = emptyList()
        val mappedItems: List<Map<String, Line>> = emptyList()

        @get:JsonSerialize(contentUsing = ScalarLineSerializer::class)
        val serializedItems: List<Line> = emptyList()
        val classSerializedItems: List<ClassSerializedLine> = emptyList()

        @get:JsonSerialize(using = ScalarLineSerializer::class)
        val serializedValue: Line = Line("")
        val classSerializedValue: ClassSerializedLine = ClassSerializedLine("")

        @get:JsonUnwrapped(prefix = "flat_")
        val unwrapped: Line = Line("")
        val registeredValue: AggregateId? = null
        val attributes: Map<String, String> = emptyMap()
    }

    private class Level1 { val level2 = Level2() }
    private class Level2 { val level3 = Level3() }
    private class Level3 { val level4 = Level4() }
    private class Level4 { val level5 = Level5() }
    private class Level5 { val level6 = Level6() }
    private class Level6 { val value: String = "" }
    private data class Order(val lines: List<Line>)
    private data class Line(val sku: String)
    private data class ScalarItem(@get:JsonValue val value: String)

    private class ScalarLineSerializer : StdSerializer<Line>(Line::class.java) {
        override fun serialize(value: Line, generator: JsonGenerator, provider: SerializationContext) {
            generator.writeString(value.sku)
        }
    }

    @JsonSerialize(using = ClassSerializedLineSerializer::class)
    private data class ClassSerializedLine(val sku: String)

    private class ClassSerializedLineSerializer : StdSerializer<ClassSerializedLine>(ClassSerializedLine::class.java) {
        override fun serialize(value: ClassSerializedLine, generator: JsonGenerator, provider: SerializationContext) {
            generator.writeString(value.sku)
        }
    }

    private class PortableState {
        val flag: Boolean = false
        val character: Char = 'a'
        val number: Int = 0
        val decimal: BigDecimal = BigDecimal.ZERO
        val status: Status = Status.ACTIVE
        val uuid: UUID = UUID(0, 0)
        val date: Date = Date(0)
        val instant: Instant = Instant.EPOCH
        val localDate: LocalDate = LocalDate.EPOCH
        val localDateTime: LocalDateTime = LocalDateTime.MIN
        val offsetDateTime: OffsetDateTime = OffsetDateTime.MIN
        val zonedDateTime: ZonedDateTime = ZonedDateTime.of(LocalDateTime.MIN, ZoneOffset.UTC)
        val scalarValue: ScalarItem = ScalarItem("")
        val numericStatus: NumericStatus = NumericStatus.ONE
        val booleanStatus: BooleanStatus = BooleanStatus.YES
        val numbers: IntArray = intArrayOf()
        val items: Array<Item> = emptyArray()
        val pet: Pet = Cat("cat")
    }

    private enum class Status { ACTIVE }

    private enum class NumericStatus(@get:JsonValue val value: Int) { ONE(1) }

    private enum class BooleanStatus(@get:JsonValue val value: Boolean) { YES(true) }

    private data class Item(val value: Long)

    @JsonSubTypes(JsonSubTypes.Type(value = Cat::class))
    private interface Pet

    private data class Cat(val name: String) : Pet
}
