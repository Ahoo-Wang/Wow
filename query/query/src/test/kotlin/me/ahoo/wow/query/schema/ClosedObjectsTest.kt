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

package me.ahoo.wow.query.schema

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * Which objects a Manifest schema closes, as `ManifestSchemaReader` alone reads it (#7243), and how a read's walk
 * answers a value of each: a union by its alternatives together, a value of another shape than its schema's left alone.
 */
class ClosedObjectsTest {
    private val field = QueryField("state")

    private val defs: Map<String, Any?> = mapOf(
        "Shelf" to classOf("Shelf", "row" to INTEGER),
        "Bin" to classOf("Bin", "bin" to INTEGER),
    )

    private fun schemaOf(state: Map<String, Any?>, model: QueryModel = QueryModel.SNAPSHOT): QueryModelSchema {
        val declaration = ManifestSchemaReader(defs, "shop", MaskStrategyResolver.NONE).declaration(state, field)
        val logical = QuerySchemaMerger().merge(
            QuerySchemaDeclaration(mapOf(field to declaration)),
            emptyList(),
            extensionRoots = null,
        )
        return QueryModelSchema(model, emptySet(), logical, emptyMap())
    }

    private fun valueOf(state: Map<String, Any?>): QueryValueSchema = schemaOf(state).root.properties.getValue("state")

    /** [stored] as a read of [state]'s schema answers it. */
    private fun read(state: Map<String, Any?>, stored: String): String {
        val record = WowJson.mapper.readTree("""{"state":$stored}""") as ObjectNode
        return checkNotNull(SchemaMasker.create(schemaOf(state))).mask(record)["state"].toString()
    }

    @Test
    fun `an object of properties is closed unless it allows others, and a free object is open`() {
        valueOf(mapOf("type" to "object", "properties" to mapOf("a" to STRING))).declaredNames
            .assert().containsExactly("a")
        valueOf(mapOf("type" to "object", "properties" to mapOf("a" to STRING), "additionalProperties" to false))
            .declaredNames.assert().containsExactly("a")
        valueOf(mapOf("type" to "object", "x-wow-class" to "shop.Empty")).declaredNames.assert().isEmpty()
        valueOf(mapOf("type" to "object")).declaredNames.assert().isNull()
        valueOf(mapOf("type" to "object", "additionalProperties" to STRING)).declaredNames.assert().isNull()
    }

    @Test
    fun `the parts of an allOf close an object together, and one that allows other properties opens it`() {
        val a = classOf("A", "a" to STRING)
        valueOf(mapOf("allOf" to listOf(a, classOf("B", "b" to STRING)))).declaredNames
            .assert().containsExactlyInAnyOrder("a", "b")
        // A part that says nothing of the object's properties, a free object, leaves the others to close it.
        val free = mapOf("type" to "object")
        valueOf(mapOf("allOf" to listOf(a, free))).declaredNames.assert().containsExactly("a")
        valueOf(mapOf("allOf" to listOf(free, a))).declaredNames.assert().containsExactly("a")
        val open = valueOf(mapOf("allOf" to listOf(a, mapOf("type" to "object", "additionalProperties" to true))))
        open.declaredNames.assert().isNull()
        checkNotNull(open.additionalProperties).kind.assert().isEqualTo(QueryValueKind.UNKNOWN)
    }

    @Test
    fun `a value of a union keeps what any alternative declares, and all of it when one is open`() {
        val closed = classOf("S", "slot" to mapOf("oneOf" to listOf(ref("Shelf"), ref("Bin"))))
        read(closed, """{"slot":{"row":1,"bin":2,"pin":3}}""").assert().isEqualTo("""{"slot":{"row":1,"bin":2}}""")
        val open = classOf("S", "slot" to mapOf("oneOf" to listOf(ref("Shelf"), mapOf("type" to "object"))))
        read(open, """{"slot":{"row":1,"pin":3}}""").assert().isEqualTo("""{"slot":{"row":1,"pin":3}}""")
        val first = classOf("S", "slot" to mapOf("oneOf" to listOf(mapOf("type" to "object"), ref("Shelf"))))
        read(first, """{"slot":{"row":1,"pin":3}}""").assert().isEqualTo("""{"slot":{"row":1,"pin":3}}""")
        // Alternatives that both walk a property walk it by both its schemas.
        val nested = classOf(
            "S",
            "slot" to mapOf(
                "oneOf" to listOf(
                    classOf("A", "inner" to ref("Shelf")),
                    classOf("B", "inner" to ref("Bin"), "b" to STRING),
                ),
            ),
        )
        read(nested, """{"slot":{"inner":{"row":1,"bin":2,"pin":3},"b":"x","c":"y"}}""")
            .assert().isEqualTo("""{"slot":{"inner":{"row":1,"bin":2},"b":"x"}}""")
        // A property only one alternative walks is walked by that alternative's schema.
        val a = classOf("A", "inner" to ref("Shelf"))
        val b = classOf("B", "b" to STRING)
        listOf(listOf(a, b), listOf(b, a)).forEach { alternatives ->
            read(
                classOf("S", "slot" to mapOf("oneOf" to alternatives)),
                """{"slot":{"inner":{"row":1,"pin":2},"b":"x"}}"""
            )
                .assert().isEqualTo("""{"slot":{"inner":{"row":1},"b":"x"}}""")
        }
        // A union of scalars holds no object to walk.
        val scalars = classOf("S", "code" to mapOf("oneOf" to listOf(STRING, INTEGER)))
        read(scalars, """{"code":{"x":1},"y":2}""").assert().isEqualTo("""{"code":{"x":1}}""")
    }

    @Test
    fun `a value of another shape than its schema's is left alone`() {
        val state = classOf(
            "S",
            "shelf" to ref("Shelf"),
            "shelves" to mapOf("type" to "array", "items" to ref("Shelf")),
            "note" to STRING,
        )
        read(state, """{"shelf":[{"row":1,"pin":2}],"shelves":{"row":1,"pin":2},"note":{"pin":3}}""")
            .assert().isEqualTo("""{"shelf":[{"row":1,"pin":2}],"shelves":{"row":1,"pin":2},"note":{"pin":3}}""")
        // An object holding fewer properties than its schema walks is walked by what it holds.
        read(state, """{"shelves":[{"row":1,"pin":2}]}""").assert().isEqualTo("""{"shelves":[{"row":1}]}""")
    }

    @Test
    fun `an open object's other properties are walked by its values' schema, its named ones by their own`() {
        val state = classOf(
            "S",
            "mixed" to mapOf(
                "type" to "object",
                "properties" to mapOf("kind" to mapOf("type" to "object")),
                "additionalProperties" to ref("Shelf"),
            ),
        )
        read(state, """{"mixed":{"kind":{"pin":1},"x":{"row":1,"pin":2}}}""")
            .assert().isEqualTo("""{"mixed":{"kind":{"pin":1},"x":{"row":1}}}""")
    }

    @Test
    fun `only a model that masks its responses through its schema walks them`() {
        val state = classOf("S", "a" to STRING)
        schemaOf(state).declaredOnly.assert().isNotNull()
        schemaOf(state, QueryModel.EVENTS).declaredOnly.assert().isNull()
        schemaOf(state, QueryModel("custom")).declaredOnly.assert().isNull()
    }

    @Test
    fun `only an object without other properties is closed`() {
        assertThrownBy<IllegalArgumentException> {
            QueryValueSchema(
                QueryValueKind.SCALAR,
                valueTypes = setOf(QueryValueType.STRING),
                declaredNames = setOf("a")
            )
        }.hasMessage("Only an object value without dynamic properties is closed.")
        val unknown = QueryValueSchema(QueryValueKind.UNKNOWN)
        assertThrownBy<IllegalArgumentException> {
            QueryValueSchema(QueryValueKind.OBJECT, additionalProperties = unknown, declaredNames = setOf("a"))
        }.hasMessage("Only an object value without dynamic properties is closed.")
        val closed = QueryValueSchema(QueryValueKind.OBJECT, declaredNames = setOf("a"))
        closed.sameDefinitionAs(QueryValueSchema(QueryValueKind.OBJECT, declaredNames = setOf("a"))).assert().isTrue()
        closed.sameDefinitionAs(QueryValueSchema(QueryValueKind.OBJECT)).assert().isFalse()
    }

    private companion object {
        val STRING = mapOf("type" to "string")
        val INTEGER = mapOf("type" to "integer")

        fun ref(name: String) = mapOf("\$ref" to Manifest.schemaRef(name))

        fun classOf(name: String, vararg properties: Pair<String, Any?>): Map<String, Any?> =
            mapOf("type" to "object", "x-wow-class" to "shop.$name", "properties" to mapOf(*properties))
    }
}
