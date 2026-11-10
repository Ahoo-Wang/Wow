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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.moneySchema
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.stateFragment
import me.ahoo.wow.manifest.tools.SchemaIntrospectorTest.Money
import me.ahoo.wow.spi.json.StateJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.URLClassLoader

/** The computed properties a state's schema holds: the getters Jackson writes, read-only, as Kotlin types them. */
class ComputedPropertiesTest {
    private val introspector = SchemaIntrospector(javaClass.classLoader, mapOf(Money::class.java.name to moneySchema))

    data class Entry(val sku: String, val quantity: Int, val price: Int) {
        val subtotal: Int get() = quantity * price
    }

    class LedgerState(val id: String) {
        var entries: List<Entry> = emptyList()
        var owner: String? = null

        /** A field's property whose getter the generator does not pair with its field (`isOpen()`): no computed one. */
        var isOpen: Boolean = true

        /** A public field and no getter: a property Jackson writes by the field alone. */
        @JvmField
        var note: String = ""
        val total: Int get() = entries.sumOf { it.subtotal }
        val empty: Boolean get() = entries.isEmpty()
        val initial: String? get() = owner?.take(1)
        val skus: List<String?> get() = entries.map { it.sku }

        /** Computed containers of a class: the generator reads their items through scopes of their own. */
        val multiples: List<Entry> get() = entries.filter { it.quantity > 1 }
        val bySku: Map<String, Entry> get() = entries.associateBy { it.sku }

        @get:Sensitive(SensitivityLevel.CONFIDENTIAL)
        val contact: String get() = "owner:$owner"

        @get:JsonProperty("count")
        val entryCount: Int get() = entries.size

        @get:JsonIgnore
        val hidden: Int get() = 1

        @Suppress("FunctionOnlyReturningConstant")
        fun compute(): Int = 2

        fun entryOf(sku: String): Entry? = entries.firstOrNull { it.sku == sku }

        companion object {
            /** A static field of the computed property's name, the companion's: it backs no instance property. */
            @Suppress("unused")
            val total: Int = 0
        }
    }

    @Test
    fun `a computed property Jackson writes should be a read-only property of the schema`() {
        val completed = introspector.complete(stateFragment(LedgerState::class.java))
        val ledger = defOf(completed, LedgerState::class.java)
        val properties = ledger["properties"] as Map<*, *>
        // Every property Jackson writes into the snapshot, and none other: not a function, not an ignored getter.
        val written = StateJson.mapper.readTree(
            StateJson.mapper.writeValueAsString(LedgerState("l").apply { entries = listOf(Entry("mug", 2, 3)) }),
        ).propertyNames().asSequence().toSet()
        properties.keys.assert().isEqualTo(written)
        properties.keys.assert().doesNotContain("hidden", "compute", "entryCount", "entryOf")
        // A field's property is the field's, written once and not read-only, with or without a getter.
        (properties["isOpen"] as Map<*, *>).keys.assert().doesNotContain("readOnly")
        properties["note"].assert().isEqualTo(mapOf("type" to "string"))
        properties["total"].assert().isEqualTo(mapOf("type" to "integer", "format" to "int32", "readOnly" to true))
        properties["empty"].assert().isEqualTo(mapOf("type" to "boolean", "readOnly" to true))
        properties["count"].assert().isEqualTo(mapOf("type" to "integer", "format" to "int32", "readOnly" to true))
        properties["initial"].assert().isEqualTo(mapOf("type" to listOf("string", "null"), "readOnly" to true))
        properties["skus"].assert().isEqualTo(
            mapOf("type" to "array", "items" to mapOf("type" to listOf("string", "null")), "readOnly" to true),
        )
        // A computed container is read-only; its items are the class's, plain, not a property of their own.
        val entryRef = mapOf("\$ref" to schemaRef(Entry::class.java.name))
        properties["multiples"].assert()
            .isEqualTo(mapOf("type" to "array", "items" to entryRef, "readOnly" to true))
        properties["bySku"].assert()
            .isEqualTo(mapOf("type" to "object", "additionalProperties" to entryRef, "readOnly" to true))
        // Its query keywords, as a field's: a sensitive one is masked.
        (properties["contact"] as Map<*, *>)["x-wow-sensitive"].assert().isEqualTo(mapOf("level" to "confidential"))
        // Written always, so required unless nullable.
        (ledger["required"] as List<*>).assert()
            .containsExactlyInAnyOrder(
                "id",
                "entries",
                "isOpen",
                "note",
                "total",
                "empty",
                "skus",
                "multiples",
                "bySku",
                "contact",
                "count",
            )
        // Of a nested class too: the class, a definition, holds its own computed property, and is not read-only.
        val entry = defOf(completed, Entry::class.java)
        entry.keys.assert().doesNotContain("readOnly")
        ((entry["properties"] as Map<*, *>)["subtotal"] as Map<*, *>)["readOnly"].assert().isEqualTo(true)
        (entry["required"] as List<*>).assert().contains("subtotal")
        // The fingerprint was the shape Jackson writes all along: computed properties included.
        completed.contexts.single().aggregates.single().state.fingerprint.assert()
            .isEqualTo(StateJson.fingerprint(LedgerState::class.java))
    }

    /** A value's key, which its interface computes. */
    interface Keyed {
        val code: String

        val key: String get() = "k-$code"
    }

    data class Label(override val code: String) : Keyed

    class LabeledState(val id: String) : Keyed {
        override val code: String = "s"
        var label: Label = Label("a")
        var keyed: Keyed = Label("b")
    }

    @Test
    fun `a property an interface's default getter computes should be in the schema as Jackson writes it`() {
        val completed = introspector.complete(stateFragment(LabeledState::class.java))
        val written = StateJson.mapper.readTree(StateJson.mapper.writeValueAsString(LabeledState("l")))
        fun propertiesOf(schema: Map<*, *>): Map<*, *> {
            val target = (schema["\$ref"] as? String)?.let { completed.defs[it.substringAfterLast('/')] as Map<*, *> }
            return (target ?: schema)["properties"] as Map<*, *>
        }
        // Of the state, of a class implementing the interface, and of a property the interface types: Jackson writes
        // the default getter's property, and the schema holds it, read-only.
        val state = propertiesOf(defOf(completed, LabeledState::class.java))
        state.keys.assert().isEqualTo(written.propertyNames().asSequence().toSet())
        listOf("label", "keyed").forEach { name ->
            val value = propertiesOf(state[name] as Map<*, *>)
            value.keys.assert().describedAs(name).isEqualTo(written.get(name).propertyNames().asSequence().toSet())
            (value["key"] as Map<*, *>)["readOnly"].assert().describedAs(name).isEqualTo(true)
        }
        (state["key"] as Map<*, *>)["readOnly"].assert().isEqualTo(true)
    }

    /** The fixtures compiled in Kotlin's JVM default [mode], `noCompatibility` or `enable`, by a loader of their own. */
    private fun jvmDefaultFixtures(mode: String): ClassLoader {
        val classes = System.getProperty("wow-test.jvm-default.$mode").split(File.pathSeparator)
        return URLClassLoader(classes.map { File(it).toURI().toURL() }.toTypedArray(), javaClass.classLoader)
    }

    @ParameterizedTest
    @ValueSource(strings = ["noCompatibility", "enable"])
    fun `computed properties should be in the schema as Jackson writes them, in each JVM default mode`(mode: String) {
        val fixtures = jvmDefaultFixtures(mode)
        val stateType = fixtures.loadClass("me.ahoo.wow.manifest.tools.jvmdefault.TicketState")
        // In `enable` mode the class repeats each default getter of its interfaces as a bridge, which the generator
        // offers, and Jackson skips for the interface's getter; in `no-compatibility` mode it holds none.
        stateType.declaredMethods.any { it.name == "getStage" && it.isBridge }.assert().isEqualTo(mode == "enable")
        val completed = SchemaIntrospector(fixtures).complete(stateFragment(stateType))
        val written = StateJson.mapper.readTree(
            StateJson.mapper.writeValueAsString(stateType.getConstructor(String::class.java).newInstance("t")),
        )
        val state = defOf(completed, stateType)
        val properties = state["properties"] as Map<*, *>
        properties.keys.assert().isEqualTo(written.propertyNames().asSequence().toSet())
        // The interface's computed properties and the class's own: read-only, and required unless nullable.
        (properties["stage"] as Map<*, *>)["readOnly"].assert().isEqualTo(true)
        properties["held"].assert().isEqualTo(mapOf("type" to "boolean", "readOnly" to true))
        properties["note"].assert().isEqualTo(mapOf("type" to listOf("string", "null"), "readOnly" to true))
        properties["empty"].assert().isEqualTo(mapOf("type" to "boolean", "readOnly" to true))
        (state["required"] as List<*>).assert()
            .containsExactlyInAnyOrder("id", "lines", "ticket", "stage", "held", "empty")
        // Of a value whose interface computes the property a generic interface declares: a string, as it is written.
        val ticketSchema = properties["ticket"] as Map<*, *>
        val ticketTarget = (ticketSchema["\$ref"] as? String)?.let { completed.defs[it.substringAfterLast('/')] }
        val ticket = ((ticketTarget ?: ticketSchema) as Map<*, *>)["properties"] as Map<*, *>
        ticket.keys.assert().isEqualTo(written.get("ticket").propertyNames().asSequence().toSet())
        ticket["id"].assert().isEqualTo(mapOf("type" to "string", "readOnly" to true))
        ticket["label"].assert().isEqualTo(mapOf("type" to "string", "readOnly" to true))
    }

    class RoutedState(val id: String) {
        var target: AggregateRef? = null
        var source: AggregateRef? = null
    }

    @Test
    fun `a getter Wow's JSON does not write should be no computed property`() {
        val completed = introspector.complete(stateFragment(RoutedState::class.java))
        // Wow writes a ref with a serializer of its own, its derived isAssigned left out.
        val ref = completed.defs["${AggregateRef::class.java.name}-nullable"] as Map<*, *>
        (ref["properties"] as Map<*, *>).keys.assert().containsExactlyInAnyOrder("id", "tenantId", "type")
    }
}
