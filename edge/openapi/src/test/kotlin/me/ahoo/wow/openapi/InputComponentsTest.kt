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

    private companion object {
        const val FIXTURE = "computed-inputs/manifest.json"
        const val PROBE = "me.ahoo.probe"
    }
}
