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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.JavaType
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import tools.jackson.databind.util.StdConverter

/**
 * A class Jackson writes with a serializer of its own has its schema declared, whatever its kind (#6372): an
 * interface, an abstract class, an enum, a converter to another class and a serializer that reports the object format
 * all stop the generation until `wow { schemaOverrides }` declares them, as a concrete class written as a scalar does.
 */
class OwnSerializerGuardTest {
    class AsText : ValueSerializer<Any>() {
        override fun serialize(value: Any, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeString(value.toString())
        }
    }

    @JsonSerialize(using = AsText::class)
    interface Shape

    data class Circle(val radius: Int) : Shape

    @JsonSerialize(using = AsText::class)
    abstract class Account {
        abstract val number: String
    }

    class Checking(override val number: String) : Account()

    class AsOrdinal : ValueSerializer<Level>() {
        override fun serialize(value: Level, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeNumber(value.ordinal)
        }
    }

    @JsonSerialize(using = AsOrdinal::class)
    enum class Level { LOW, HIGH }

    data class Wire(val code: String, val cents: Long)

    class ToWire : StdConverter<Price, Wire>() {
        override fun convert(value: Price): Wire = Wire(value.currency, value.amount)
    }

    @JsonSerialize(converter = ToWire::class)
    class Price(val currency: String, val amount: Long)

    /** Writes a point as `{"xy": "x,y"}`, and tells Jackson it writes an object. */
    class CompactObject : ValueSerializer<Point>() {
        override fun serialize(value: Point, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeStartObject()
            gen.writeStringProperty("xy", "${value.x},${value.y}")
            gen.writeEndObject()
        }

        override fun acceptJsonFormatVisitor(visitor: JsonFormatVisitorWrapper, typeHint: JavaType) {
            visitor.expectObjectFormat(typeHint)
        }
    }

    @JsonSerialize(using = CompactObject::class)
    class Point(val x: Int, val y: Int)

    class ShapeState(val id: String, val shape: Shape)

    class CircleState(val id: String, val circle: Circle)

    class AccountState(val id: String, val account: Account)

    class LevelState(val id: String, val level: Level)

    class PriceState(val id: String, val price: Price)

    class PointState(val id: String, val point: Point)

    /** Plain kinds the generator describes as Jackson writes them: no serializer of their own. */
    interface Named {
        val name: String
    }

    abstract class Base {
        abstract val code: String
    }

    enum class Color { RED, BLUE }

    class PlainState(val id: String, val named: Named?, val base: Base?, val color: Color, val wire: Wire)

    private val introspector = SchemaIntrospector(javaClass.classLoader)

    private fun refused(state: Class<*>, type: Class<*>) {
        val failure = assertThrows<IllegalStateException> { introspector.complete(stateFragment(state)) }
        failure.message.assert().isEqualTo(
            "Jackson writes ${type.name} with a serializer of its own (@JsonSerialize using or converter), so its " +
                "schema cannot be introspected: declare it in wow { schemaOverrides.put(\"${type.name}\", " +
                "\"<JSON Schema>\") }.",
        )
    }

    @Test
    fun `an interface, an abstract class or an enum with a serializer of its own should have its schema declared`() {
        refused(ShapeState::class.java, Shape::class.java)
        refused(AccountState::class.java, Account::class.java)
        refused(LevelState::class.java, Level::class.java)
        // A concrete class that inherits its interface's serializer is written as a scalar, which the guard refuses
        // first, as before.
        assertThrows<IllegalStateException> { introspector.complete(stateFragment(CircleState::class.java)) }
            .message.assert().startsWith("Jackson does not write ${Circle::class.java.name} as an object")
            .contains("schemaOverrides.put(\"${Circle::class.java.name}\"")
    }

    @Test
    fun `a converter or a serializer that reports the object format should have its schema declared`() {
        refused(PriceState::class.java, Price::class.java)
        refused(PointState::class.java, Point::class.java)
    }

    @Test
    fun `a declared schema should stand for the class, whatever its kind`() {
        val declared = mapOf(
            Shape::class.java.name to """{"type":"string"}""",
            Level::class.java.name to """{"type":"integer","enum":[0,1]}""",
            Price::class.java.name to
                """{"type":"object","properties":{"code":{"type":"string"},"cents":{"type":"integer"}}}""",
        )
        val declaring = SchemaIntrospector(javaClass.classLoader, declared)
        listOf(
            ShapeState::class.java to Shape::class.java,
            LevelState::class.java to Level::class.java,
            PriceState::class.java to Price::class.java,
        ).forEach { (state, type) ->
            val schema = defOf(declaring.complete(stateFragment(state)), state)["properties"] as Map<*, *>
            val property = schema.values.last() as Map<*, *>
            property.filterKeys { it != "x-wow-class" }.assert()
                .isEqualTo(JsonMapper.builder().build().readValue(declared.getValue(type.name), Map::class.java))
        }
    }

    @Test
    fun `an interface, an abstract class and an enum without a serializer of their own should pass as before`() {
        val properties = defOf(introspector.complete(stateFragment(PlainState::class.java)), PlainState::class.java)
        (properties["properties"] as Map<*, *>).keys.assert()
            .containsExactlyInAnyOrder("id", "named", "base", "color", "wire")
    }
}
