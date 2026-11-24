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

package me.ahoo.wow.openapi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.ManifestTree
import me.ahoo.wow.manifest.RouteManifest
import me.ahoo.wow.manifest.RouteParameterManifest
import me.ahoo.wow.manifest.ServiceRouteManifest
import me.ahoo.wow.manifest.SystemCommands
import me.ahoo.wow.manifest.copyWith
import org.junit.jupiter.api.Test

/**
 * A request's schema is the input of each type that holds a computed property (`readOnly`), at any depth: the property
 * stays known, its schema and `readOnly` kept, and is never required, since the service writes it and reads nothing
 * into it. The fixture, `computed-inputs/manifest.json`, is also what wow-generator's probe of the same rule reads
 * (`test/probes/inputParity.test.ts`), so both projections name the same types as inputs.
 */
class InputComponentsTest {
    private val manifest: Manifest = ManifestTree.read(
        OpenApiChecks.mapper.readValue(
            checkNotNull(javaClass.classLoader.getResourceAsStream(FIXTURE)) { "$FIXTURE is not on the class path" },
            Map::class.java,
        ),
        ManifestTree.REFUSE,
    )
    private val document = ManifestOpenApi.write(manifest)

    @Suppress("UNCHECKED_CAST")
    private val schemas = (document["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun requestSchema(path: String): Any? {
        val post = (document["paths"] as Map<String, Map<String, Map<String, Any?>>>).getValue(path).getValue("post")
        val content = (post["requestBody"] as Map<String, Any?>)["content"] as Map<String, Map<String, Any?>>
        return content.getValue("application/json")["schema"]
    }

    private fun ref(name: String) = mapOf("\$ref" to "#/components/schemas/$name")

    @Test
    fun `the document should be well formed`() {
        OpenApiChecks.check(document)
    }

    @Test
    fun `the types a caller sends that hold a computed property, at any depth, should each have an input`() {
        SchemaComponents(manifest.defs, emptySet(), SchemaComponents.inputRoots(manifest)).inputs.assert()
            .isEqualTo(
                mapOf(
                    "$PROBE.Card" to "$PROBE.CardInput",
                    "$PROBE.CreateOrder" to "$PROBE.CreateOrderInput",
                    // The service's own type of that name keeps it; the input takes the first free suffix.
                    "$PROBE.ProductRef" to "$PROBE.ProductRefInput_2",
                    "$PROBE.Search" to "$PROBE.SearchInput",
                ),
            )
        schemas.keys.filter { it.startsWith(PROBE) }.assert().containsExactly(
            "$PROBE.Audit",
            "$PROBE.Card",
            "$PROBE.CardInput",
            "$PROBE.CreateOrder",
            "$PROBE.CreateOrderInput",
            "$PROBE.OrderState",
            "$PROBE.ProductRef",
            "$PROBE.ProductRefInput_2",
            "$PROBE.ProductRefInput",
            "$PROBE.Rename",
            "$PROBE.Search",
            "$PROBE.SearchInput",
            "$PROBE.Wallet",
        )
    }

    @Test
    fun `an input should keep a computed property, not required, and refer to the inputs`() {
        schemas.getValue("$PROBE.ProductRefInput_2").assert().isEqualTo(
            mapOf(
                "type" to "object",
                "required" to listOf("id", "code", "brandName"),
                "properties" to mapOf(
                    "id" to mapOf("type" to "string"),
                    "code" to mapOf("type" to "string"),
                    "brandName" to mapOf("type" to "string"),
                    "searchCode" to mapOf("type" to "string", "readOnly" to true),
                ),
            ),
        )
        val input = schemas.getValue("$PROBE.CreateOrderInput")
        input["required"].assert().isEqualTo(listOf("product", "lines", "byCode", "payment", "note"))
        input["properties"].assert().isEqualTo(
            mapOf(
                "product" to ref("$PROBE.ProductRefInput_2"),
                "lines" to mapOf("type" to "array", "items" to ref("$PROBE.ProductRefInput_2")),
                "byCode" to mapOf("type" to "object", "additionalProperties" to ref("$PROBE.ProductRefInput_2")),
                "payment" to mapOf("oneOf" to listOf(ref("$PROBE.CardInput"), ref("$PROBE.Wallet"))),
                "note" to mapOf(
                    "type" to "object",
                    "required" to listOf("text"),
                    "properties" to mapOf(
                        "text" to mapOf("type" to "string"),
                        "length" to mapOf("type" to "string", "readOnly" to true),
                    ),
                ),
                "label" to mapOf("type" to "string", "readOnly" to true),
            ),
        )
    }

    @Test
    fun `what the service writes should keep its computed properties required`() {
        schemas.getValue("$PROBE.ProductRef")["required"].assert()
            .isEqualTo(listOf("id", "code", "brandName", "searchCode"))
        schemas.getValue("$PROBE.OrderState")["properties"].assert().isEqualTo(
            mapOf(
                "id" to mapOf("type" to "string"),
                "product" to ref("$PROBE.ProductRef"),
                "audit" to ref("$PROBE.Audit"),
            ),
        )
    }

    @Test
    fun `a request should refer to the input, and a response to the type`() {
        requestSchema("/probe/thing").assert().isEqualTo(ref("$PROBE.CreateOrderInput"))
        requestSchema("/probe/thing/{id}/rename").assert().isEqualTo(ref("$PROBE.Rename"))
        requestSchema("/probe/search").assert().isEqualTo(ref("$PROBE.SearchInput"))
        OpenApiChecks.mapper.writeValueAsString(document["paths"]).assert()
            .contains("\"#/components/schemas/$PROBE.ProductRefInput\"")
    }

    @Test
    fun `a body should validate with its computed properties left out or handed back`() {
        val product = """{"id":"p","code":"c","brandName":"b"}"""
        val handedBack = """{"id":"p","code":"c","brandName":"b","searchCode":"c|b"}"""
        fun body(product: String, extra: String = "") =
            """{"product":$product,"lines":[$product],"byCode":{"c":$product},"payment":{"kind":"card"},""" +
                """"note":{"text":"n"}$extra}"""
        OpenApiChecks.validate(document, "$PROBE.CreateOrderInput", body(product)).assert().isEmpty()
        OpenApiChecks.validate(
            document,
            "$PROBE.CreateOrderInput",
            body(handedBack, ",\"label\":\"l\"").replace("\"card\"}", "\"card\",\"masked\":\"**42\"}"),
        ).assert().isEmpty()
        // The type itself, what the service writes, requires them.
        OpenApiChecks.validate(document, "$PROBE.CreateOrder", body(product)).assert().isNotEmpty()
        // A property the service reads is still required.
        OpenApiChecks.validate(document, "$PROBE.CreateOrderInput", body("""{"id":"p","code":"c"}"""))
            .assert().isNotEmpty()
    }

    private val computed = mapOf("type" to "string", "readOnly" to true)

    @Test
    fun `a property named required, a property any value takes and an enum should read as in any schema`() {
        val odd = mapOf(
            "type" to "object",
            "required" to listOf("required", "any", "computed"),
            "properties" to mapOf(
                "required" to mapOf("type" to "string"),
                "any" to true,
                "kind" to mapOf("enum" to listOf("a", "b")),
                "computed" to computed,
            ),
        )
        val plain = mapOf("type" to "object", "properties" to mapOf("kind" to mapOf("enum" to listOf("a"))))
        // A computed property only inside an inline object: the type holds it all the same.
        val nested = mapOf(
            "type" to "object",
            "properties" to mapOf("note" to mapOf("type" to "object", "properties" to mapOf("length" to computed))),
        )
        val components = SchemaComponents(
            mapOf("t.Odd" to odd, "t.Plain" to plain, "t.Nested" to nested),
            emptySet(),
            setOf("t.Odd", "t.Plain", "t.Nested"),
        )
        components.inputs.assert().isEqualTo(mapOf("t.Nested" to "t.NestedInput", "t.Odd" to "t.OddInput"))
        @Suppress("UNCHECKED_CAST")
        val input = components.schemas.getValue("t.OddInput") as Map<String, Any?>
        input["required"].assert().isEqualTo(listOf("required", "any"))
        input["properties"].assert().isEqualTo(odd["properties"])
    }

    @Test
    fun `an object whose only required properties are computed should require nothing in its input`() {
        val lengths = mapOf("length" to computed)
        val note = mapOf("type" to "object", "required" to listOf("length"), "properties" to lengths)
        val only = mapOf(
            "type" to "object",
            "required" to listOf("hash"),
            "properties" to mapOf("hash" to computed, "note" to note),
        )
        val components = SchemaComponents(mapOf("t.Only" to only), emptySet(), setOf("t.Only"))
        components.schemas.getValue("t.Only").assert().isEqualTo(only)
        // An object that requires nothing has no `required`, in the component and in an inline object of it.
        components.schemas.getValue("t.OnlyInput").assert().isEqualTo(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "hash" to computed,
                    "note" to mapOf("type" to "object", "properties" to lengths),
                ),
            ),
        )
        components.inlineInput(note).assert().isEqualTo(mapOf("type" to "object", "properties" to lengths))
    }

    @Test
    fun `a name an object requires without declaring it should stay required, whatever a sibling says of it`() {
        val split = mapOf(
            "allOf" to listOf(
                mapOf("type" to "object", "properties" to mapOf("a" to computed, "b" to mapOf("type" to "string"))),
                mapOf("required" to listOf("a", "b")),
            ),
        )
        val components = SchemaComponents(mapOf("t.Split" to split), emptySet(), setOf("t.Split"))
        components.inputs.assert().isEqualTo(mapOf("t.Split" to "t.SplitInput"))
        components.schemas.getValue("t.SplitInput").assert().isEqualTo(split)
    }

    @Test
    fun `a route's parameter and a system command the framework does not declare should take their inputs`() {
        fun holding(name: String) = mapOf(
            "type" to "object",
            "required" to listOf(name, "hash"),
            "properties" to mapOf(name to mapOf("type" to "string"), "hash" to computed),
        )
        val context = manifest.contexts.single()
        val extended = manifest.copyWith(
            contexts = listOf(
                context.copyWith(
                    aggregates = context.aggregates.map {
                        it.copyWith(commands = it.commands + SystemCommands.lifecycle("probe", "thing", ARCHIVE))
                    },
                    routes = context.routes + ServiceRouteManifest(
                        name = "find",
                        route = RouteManifest("GET", "/probe/find"),
                        parameters = listOf(
                            RouteParameterManifest(
                                "filter",
                                RouteParameterManifest.Location.QUERY,
                                mapOf("\$ref" to "#/\$defs/$PROBE.Filter"),
                            ),
                        ),
                    ),
                ),
            ),
            defs = manifest.defs + ("$PROBE.Filter" to holding("q")) + (ARCHIVE to holding("reason")),
        )
        val written = ManifestOpenApi.write(extended)
        OpenApiChecks.check(written)
        @Suppress("UNCHECKED_CAST")
        val paths = written["paths"] as Map<String, Map<String, Map<String, Any?>>>

        @Suppress("UNCHECKED_CAST")
        val parameters = paths.getValue("/probe/find").getValue("get")["parameters"] as List<Map<String, Any?>>
        parameters.first()["schema"].assert().isEqualTo(ref("$PROBE.FilterInput"))
        @Suppress("UNCHECKED_CAST")
        val archive = paths.getValue("/probe/thing/{id}/wow.lifecycle.archive").getValue("post")["requestBody"]
            as Map<String, Map<String, Map<String, Any?>>>
        archive.getValue("content").getValue("application/json")["schema"].assert().isEqualTo(ref("$ARCHIVE" + "Input"))
    }

    private companion object {
        const val ARCHIVE = "wow.lifecycle.Archive"
        const val FIXTURE = "computed-inputs/manifest.json"
        const val PROBE = "me.ahoo.probe"
    }
}
