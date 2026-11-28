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

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonUnwrapped
import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import me.ahoo.wow.spi.json.StateJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.annotation.JsonAppend

/** A state whose any-getter method writes its extras beside its properties. */
class ExtrasByMethod(val sku: String) {
    private val extras = linkedMapOf<String, Any?>("card" to "c-1")

    @JsonAnyGetter
    fun extras(): Map<String, Any?> = extras

    @JsonAnySetter
    fun put(name: String, value: Any?) {
        extras[name] = value
    }
}

/** A state whose any-getter is a property's getter. */
class ExtrasByProperty(val sku: String) {
    @get:JsonAnyGetter
    val extras: Map<String, Int> = mapOf("count" to 1)
}

/** A state a writer appends an attribute to. */
@JsonAppend(attrs = [JsonAppend.Attr(value = "appended")])
data class Appended(val sku: String)

data class Address(val city: String, val zip: String)

/** A state that writes its address's properties as its own. */
data class Unwrapped(val sku: String, @get:JsonUnwrapped val address: Address)

/** A state Jackson writes as its properties alone. */
data class Plain(val sku: String)

/**
 * A class Jackson writes more of than its properties (`@JsonAnyGetter`, `@JsonAppend`) is described as open
 * (`additionalProperties: true`): Wow reads a class's schema without it as closed, which a masked read answers only
 * the declared properties of. A class Jackson writes as its properties alone keeps its closed schema, `@JsonUnwrapped`
 * included, whose nested properties the schema already declares.
 */
class OpenClassSchemasTest {
    private fun schemaOf(type: Class<*>): Map<String, Any?> =
        defOf(SchemaIntrospector(javaClass.classLoader).complete(stateFragment(type)), type)

    /** What Wow's JSON writes of [value]: the names of its properties. */
    private fun written(value: Any): Set<String> = StateJson.mapper.writer().withAttribute("appended", "a-1")
        .writeValueAsString(value).let { StateJson.mapper.readTree(it).propertyNames().toSet() }

    /** The names [schema] declares: its properties' and those of each `allOf` part. */
    private fun declared(schema: Map<*, *>): Set<String> =
        (schema["properties"] as? Map<*, *>).orEmpty().keys.map { it as String }.toSet() +
            (schema["allOf"] as? List<*>).orEmpty().flatMap { declared(it as Map<*, *>) }

    @Test
    fun `a class with an any-getter method is open, and its properties are kept`() {
        val schema = schemaOf(ExtrasByMethod::class.java)
        written(ExtrasByMethod("s")).assert().containsExactly("sku", "card")
        schema["additionalProperties"].assert().isEqualTo(true)
        declared(schema).assert().containsExactly("sku")
        schema["x-wow-class"].assert().isEqualTo(ExtrasByMethod::class.java.name)
    }

    @Test
    fun `a class whose any-getter is a property's getter is open`() {
        val schema = schemaOf(ExtrasByProperty::class.java)
        written(ExtrasByProperty("s")).assert().containsExactly("sku", "count")
        schema["additionalProperties"].assert().isEqualTo(true)
        // The getter's own name is listed as the generator wrote it before: Jackson never writes it.
        declared(schema).assert().contains("sku")
    }

    @Test
    fun `a class with JsonAppend is open`() {
        val schema = schemaOf(Appended::class.java)
        written(Appended("s")).assert().containsExactly("sku", "appended")
        schema["additionalProperties"].assert().isEqualTo(true)
        declared(schema).assert().containsExactly("sku")
    }

    @Test
    fun `an unwrapped property's properties are declared, and the class stays closed`() {
        val schema = schemaOf(Unwrapped::class.java)
        val written = written(Unwrapped("s", Address("c", "z")))
        written.assert().containsExactlyInAnyOrder("sku", "city", "zip")
        declared(schema).assert().containsExactlyInAnyOrderElementsOf(written)
        schema.containsKey("additionalProperties").assert().isFalse()
    }

    @Test
    fun `a class Jackson writes as its properties alone stays closed`() {
        val schema = schemaOf(Plain::class.java)
        declared(schema).assert().containsExactlyInAnyOrderElementsOf(written(Plain("s")))
        schema.containsKey("additionalProperties").assert().isFalse()
        // Nor is a map or a nested class opened.
        schemaOf(Address::class.java).containsKey("additionalProperties").assert().isFalse()
    }
}
