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
import com.fasterxml.jackson.annotation.JsonRawValue
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.annotation.QueryAlias
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.ToStringSerializer
import tools.jackson.databind.util.StdConverter

/**
 * A property Jackson writes with a serializer of its own has its schema declared (#6377), by the key of the class
 * that declares Jackson's accessor, `#` and the property's declared name; the declared schema stands for the whole
 * property, and wins over its type's.
 */
class PropertySerializerGuardTest {
    data class Plain(val code: String, val cents: Long)

    data class Wire(val label: String)

    class ToWire : StdConverter<Plain, Wire>() {
        override fun convert(value: Plain): Wire = Wire("${value.code}:${value.cents}")
    }

    class KeyText : ValueSerializer<Long>() {
        override fun serialize(value: Long, gen: JsonGenerator, ctxt: SerializationContext) {
            gen.writeName("k-$value")
        }
    }

    class FieldState(@field:JsonSerialize(using = ToStringSerializer::class) val id: Long)

    class ParameterState(@JsonSerialize(using = ToStringSerializer::class) val ref: Long)

    class GetterState(@get:JsonSerialize(using = ToStringSerializer::class) val viaGetter: Long)

    class ConverterState(@field:JsonSerialize(converter = ToWire::class) val converted: Plain)

    class ContentState(@field:JsonSerialize(contentUsing = ToStringSerializer::class) val ids: List<Long>)

    class ContentConverterState(@field:JsonSerialize(contentConverter = ToWire::class) val contents: List<Plain>)

    class RawState(@field:JsonRawValue val raw: String)

    class KeyState(@field:JsonSerialize(keyUsing = KeyText::class) val byKey: Map<Long, Int>)

    open class Base(@field:JsonSerialize(using = ToStringSerializer::class) val baseId: Long) {
        @get:JsonSerialize(using = ToStringSerializer::class)
        open val code: Long = 1
    }

    class Sub(baseId: Long) : Base(baseId)

    class Overriding(baseId: Long, override val code: Long) : Base(baseId)

    interface Identified {
        @get:JsonSerialize(using = ToStringSerializer::class)
        val ident: Long
    }

    class Implementing(override val ident: Long) : Identified

    class RenamedState(
        @JsonProperty("order_no")
        @field:JsonSerialize(using = ToStringSerializer::class)
        val number: Long,
    )

    @JvmInline
    value class Sku(val value: String)

    class PlainState(
        val id: String,
        val sku: Sku,
        @QueryAlias("count") val total: Long,
        val price: Plain,
        val note: Long?,
    )

    class PlatformState(
        val pair: Pair<String, Long>,
        @field:JsonRawValue(false) val text: String,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val secret: String,
    )

    private val introspector = SchemaIntrospector(javaClass.classLoader)

    private fun refused(state: Class<*>, key: String) {
        val failure = assertThrows<IllegalStateException> { introspector.complete(stateFragment(state)) }
        failure.message.assert().isEqualTo(
            "Jackson writes $key with a serializer of its own (@JsonSerialize using, converter, contentUsing or " +
                "contentConverter, or @JsonRawValue), so its schema cannot be introspected: declare it in wow { " +
                "schemaOverrides.put(\"$key\", \"<JSON Schema>\") }.",
        )
    }

    private fun key(type: Class<*>, property: String) = "${type.name}#$property"

    @Test
    fun `a property written by a serializer of its own should have its schema declared, in each form`() {
        refused(FieldState::class.java, key(FieldState::class.java, "id"))
        refused(ParameterState::class.java, key(ParameterState::class.java, "ref"))
        refused(GetterState::class.java, key(GetterState::class.java, "viaGetter"))
        refused(ConverterState::class.java, key(ConverterState::class.java, "converted"))
        refused(ContentState::class.java, key(ContentState::class.java, "ids"))
        refused(ContentConverterState::class.java, key(ContentConverterState::class.java, "contents"))
        refused(RawState::class.java, key(RawState::class.java, "raw"))
    }

    @Test
    fun `the key should name the class that declares Jackson's accessor, and the declared name`() {
        // Inherited: the declaring class names it, once for every subclass.
        refused(Sub::class.java, key(Base::class.java, "baseId"))
        // Overridden: the overriding class declares the accessor, the annotation inherited from its supertype.
        val overriding = SchemaIntrospector(javaClass.classLoader, mapOf(key(Base::class.java, "baseId") to STRING))
        assertThrows<IllegalStateException> { overriding.complete(stateFragment(Overriding::class.java)) }
            .message.assert().contains("\"${key(Overriding::class.java, "code")}\"")
        // An interface's annotated getter: each class that implements it.
        refused(Implementing::class.java, key(Implementing::class.java, "ident"))
        // The declared name, not the JSON name.
        refused(RenamedState::class.java, key(RenamedState::class.java, "number"))
    }

    @Test
    fun `a key serializer, a value class and a plain property should pass as before`() {
        defOf(introspector.complete(stateFragment(KeyState::class.java)), KeyState::class.java).assert().isNotNull()
        // A platform class's properties, @JsonRawValue(false) and a property Jackson only reads pass.
        defOf(introspector.complete(stateFragment(PlatformState::class.java)), PlatformState::class.java)
            .assert().isNotNull()
        val plain = defOf(introspector.complete(stateFragment(PlainState::class.java)), PlainState::class.java)
        (plain["properties"] as Map<*, *>).keys.assert()
            .containsExactlyInAnyOrder("id", "sku", "total", "price", "note")
    }

    @Test
    fun `a declared schema should stand for the whole property, null and its query keywords added`() {
        val declared = SchemaIntrospector(
            javaClass.classLoader,
            mapOf(
                key(ContentState::class.java, "ids") to """{"type":"array","items":{"type":"string"}}""",
                key(RenamedState::class.java, "number") to STRING,
                key(PlainState::class.java, "total") to STRING,
                key(PlainState::class.java, "note") to STRING,
            ),
        )
        properties(declared, ContentState::class.java)["ids"].assert()
            .isEqualTo(mapOf("type" to "array", "items" to mapOf("type" to "string")))
        properties(declared, RenamedState::class.java)["order_no"].assert().isEqualTo(mapOf("type" to "string"))
        // A key stands for its property whether a serializer writes it or not, its query keywords kept.
        val plain = properties(declared, PlainState::class.java)
        plain["total"].assert().isEqualTo(mapOf("type" to "string", "x-wow-alias" to listOf("count")))
        // A property that can be null gets null added, as any such property does.
        plain["note"].assert().isEqualTo(mapOf("type" to listOf("string", "null")))
    }

    @Test
    fun `a property's key should win over its type's, and a key that names nothing be ignored`() {
        val declared = SchemaIntrospector(
            javaClass.classLoader,
            mapOf(
                Plain::class.java.name to """{"type":"object","properties":{"code":{"type":"string"}}}""",
                key(PlainState::class.java, "price") to STRING,
                "com.example.Missing#id" to STRING,
                "com.example.Missing" to STRING,
            ),
        )
        properties(declared, PlainState::class.java)["price"].assert().isEqualTo(mapOf("type" to "string"))
    }

    private fun properties(introspector: SchemaIntrospector, state: Class<*>): Map<*, *> =
        defOf(introspector.complete(stateFragment(state)), state)["properties"] as Map<*, *>

    private companion object {
        const val STRING = """{"type":"string"}"""
    }
}
