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
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.Manifest.Companion.schemaRef
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.tools.SchemaIntrospectorFixtures.defOf
import me.ahoo.wow.spi.json.StateJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.databind.node.StringNode
import tools.jackson.databind.ser.std.ToStringSerializer

/**
 * A Jackson tree, Wow's JSON's own free shape, is described as Wow's JSON writes it, the JSON it holds, with no
 * declaration: an `ObjectNode` an object, an `ArrayNode` an array, any other `JsonNode` any value, named by no class.
 * A declared schema of one wins; a Jackson 2 tree, which Wow's JSON writes as a bean of its getters, is refused.
 */
class JacksonTreeSchemasTest {
    class TreeState(
        val payload: ObjectNode,
        val rows: ArrayNode,
        val value: JsonNode,
        val label: StringNode,
        val extra: ObjectNode? = null,
        val pages: List<ObjectNode> = emptyList(),
        val byKey: Map<String, JsonNode> = emptyMap(),
    )

    data class Attach(val payload: ObjectNode, val rows: ArrayNode? = null)

    data class Attached(val value: JsonNode, val payload: ObjectNode)

    class LegacyState(val legacy: com.fasterxml.jackson.databind.node.ObjectNode? = null)

    @JsonSerialize(using = ToStringSerializer::class)
    class Code(val value: String)

    class CodeState(val code: Code)

    private val any = emptyMap<String, Any?>()
    private val obj = mapOf("type" to "object")
    private val array = mapOf("type" to "array")

    private fun fragment(state: Class<*>, commands: List<Class<*>> = emptyList(), events: List<Class<*>> = emptyList()) =
        Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            "plan",
                            state = StateManifest(schemaRef(state.name)),
                            commands = commands.map { CommandManifest(it.simpleName, schema = schemaRef(it.name)) },
                            events = events.map { EventManifest(it.simpleName, schema = schemaRef(it.name)) },
                        ),
                    ),
                ),
            ),
        )

    private fun properties(manifest: Manifest, type: Class<*>): Map<*, *> =
        defOf(manifest, type)["properties"] as Map<*, *>

    @Test
    fun `a tree in a state, a command or an event should be described as the JSON it holds, with no declaration`() {
        val completed = SchemaIntrospector(javaClass.classLoader).complete(
            fragment(TreeState::class.java, listOf(Attach::class.java), listOf(Attached::class.java)),
        )
        val state = properties(completed, TreeState::class.java)
        state["payload"].assert().isEqualTo(obj)
        state["rows"].assert().isEqualTo(array)
        state["value"].assert().isEqualTo(any)
        // A value node is any value too: the schema of a JsonNode it is.
        state["label"].assert().isEqualTo(any)
        state["extra"].assert().isEqualTo(mapOf("type" to listOf("object", "null")))
        state["pages"].assert().isEqualTo(mapOf("type" to "array", "items" to obj))
        state["byKey"].assert().isEqualTo(mapOf("type" to "object", "additionalProperties" to any))
        val command = properties(completed, Attach::class.java)
        command["payload"].assert().isEqualTo(obj)
        command["rows"].assert().isEqualTo(mapOf("type" to listOf("array", "null")))
        val event = properties(completed, Attached::class.java)
        event["value"].assert().isEqualTo(any)
        event["payload"].assert().isEqualTo(obj)
        // Named by no class: no definition of a tree, as of no free shape.
        completed.defs.keys.filter { it.startsWith("tools.jackson") }.assert().isEmpty()
        // As Wow's JSON writes them.
        val tree = StateJson.mapper.createObjectNode().put("a", 1)
        StateJson.mapper.writeValueAsString(Attach(tree, StateJson.mapper.createArrayNode().add(1)))
            .assert().isEqualTo("""{"payload":{"a":1},"rows":[1]}""")
    }

    @Test
    fun `a declared schema of a tree should win, as of any class`() {
        val declared = """{"type":"object","properties":{"kind":{"type":"string"}}}"""
        val completed = SchemaIntrospector(javaClass.classLoader, mapOf(ObjectNode::class.java.name to declared))
            .complete(fragment(TreeState::class.java))
        val state = properties(completed, TreeState::class.java)
        val reference = mapOf("\$ref" to schemaRef(ObjectNode::class.java.name))
        state["payload"].assert().isEqualTo(reference)
        defOf(completed, ObjectNode::class.java)["properties"].assert()
            .isEqualTo(mapOf("kind" to mapOf("type" to "string")))
        state["rows"].assert().isEqualTo(array)
    }

    @Test
    fun `a Jackson 2 tree should be refused, declared or not, since Wow's JSON writes it as a bean`() {
        val legacy = com.fasterxml.jackson.databind.node.ObjectNode::class.java.name
        val refusal = "Wow's JSON is Jackson 3's, which writes $legacy, a Jackson 2 tree, as a bean of its getters " +
            "(\"nodeType\", \"object\", ...), not as the JSON it holds: hold a tools.jackson.databind.JsonNode, " +
            "ObjectNode or ArrayNode instead."
        listOf(emptyMap(), mapOf(legacy to """{"type":"object"}""")).forEach { overrides ->
            assertThrows<IllegalStateException> {
                SchemaIntrospector(javaClass.classLoader, overrides).complete(fragment(LegacyState::class.java))
            }.message.assert().isEqualTo(refusal)
        }
        // What Wow's JSON would write of it: its getters, not its content.
        val written = StateJson.mapper.writeValueAsString(
            LegacyState(com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("a", 1)),
        )
        written.assert().contains("\"nodeType\":\"OBJECT\"").doesNotContain("\"a\":1")
    }

    @Test
    fun `an application class with a serializer of its own should still be refused`() {
        assertThrows<IllegalStateException> {
            SchemaIntrospector(javaClass.classLoader).complete(fragment(CodeState::class.java))
        }.message.assert().isEqualTo(
            "Jackson does not write ${Code::class.java.name} as an object (a custom serializer or @JsonValue), so " +
                "its schema cannot be introspected: declare it in wow { schemaOverrides.put(" +
                "\"${Code::class.java.name}\", \"<JSON Schema>\") }.",
        )
    }
}
