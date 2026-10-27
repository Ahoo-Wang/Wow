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

package me.ahoo.wow.http

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.result.FrameworkCodes
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.runtime.admission.CommandInput
import me.ahoo.wow.runtime.admission.CommandTarget
import me.ahoo.wow.runtime.admission.CommandTransformer
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * The routes of transformed and mounted commands (decision D182), as the Manifest lists them, and what their results
 * say over HTTP.
 */
class TransformedRoutesTest {
    private val openCart = CommandType("$CONTEXT.cart.OpenCart", OpenCart::class.java)

    /** Imports a cart: a mounted command that becomes [OpenCart], to the aggregate the request names, if any. */
    private val importer = transformer(CommandType("$CONTEXT.cart.ImportCart", ImportCart::class.java)) { input ->
        CommandTarget(openCart, input.aggregateId, OpenCart((input.body as ImportCart).legacyNote), input.headers)
    }

    /** Checks out the cart whose id the note names, or none when it names `missing`. */
    private val checkout = transformer(CommandType("$CONTEXT.cart.Checkout", Checkout::class.java)) { input ->
        val note = (input.body as Checkout).note
        CommandTarget(input.type, input.aggregateId ?: note.takeIf { it != "missing" }, input.body, input.headers)
    }

    private val node = HttpNode(
        manifest = cartManifest(mounted = true, unaddressed = setOf("Checkout")),
        transformers = listOf(importer, checkout),
        mounted = true,
    )

    @Test
    fun `a mounted command is sent without an id, and its result names the input`() {
        val answer = node.post("/shop/cart/import-cart", """{"legacyNote":"from v9"}""")
        answer.status.assert().isEqualTo(200)
        val result = answer.body!!
        result["function"]["name"].asString().assert().isEqualTo("OpenCart")
        result["results"]["opened"].asString().assert().isEqualTo("from v9")
        result["transformedFrom"]["command"].asString().assert().isEqualTo("$CONTEXT.cart.ImportCart")
        result["transformedFrom"]["aggregateId"].isNull.assert().isTrue()
        node.commands.single().headers[CommandTransformer.TRANSFORMED_FROM].assert()
            .isEqualTo("$CONTEXT.cart.ImportCart")
    }

    @Test
    fun `a mounted command is sent with an id too`() {
        val answer = node.post("/shop/cart/legacy-1/import-cart", """{"legacyNote":"x"}""")
        answer.status.assert().isEqualTo(200)
        answer.body!!["ref"]["id"].asString().assert().isEqualTo("legacy-1")
        answer.body!!["transformedFrom"]["aggregateId"].asString().assert().isEqualTo("legacy-1")
    }

    @Test
    fun `a transformed command is sent without an id, the transformer finding its aggregate`() {
        node.post("/shop/cart/c-9/add-item", """{"sku":"apple"}""").status.assert().isEqualTo(200)
        val answer = node.post("/shop/cart/checkout", """{"note":"c-9"}""")
        answer.status.assert().isEqualTo(200)
        answer.body!!["ref"]["id"].asString().assert().isEqualTo("c-9")
        answer.body!!["transformedFrom"]["command"].asString().assert().isEqualTo("$CONTEXT.cart.Checkout")
    }

    @Test
    fun `an aggregate the transformer does not find is a 404 problem with the result`() {
        val answer = node.post("/shop/cart/checkout", """{"note":"missing"}""")
        answer.status.assert().isEqualTo(404)
        answer.body!!["code"].asString().assert().isEqualTo(FrameworkCodes.AGGREGATE_NOT_FOUND)
        answer.body!!["result"]["transformedFrom"]["command"].asString().assert().isEqualTo("$CONTEXT.cart.Checkout")
    }

    @Test
    fun `the served Manifest lists the mounted command and an unaddressed command's route without an id`() {
        val commands = node.get("/wow/manifest").body!!["contexts"][0]["aggregates"][0]["commands"]
        val byName = (0 until commands.size()).map { commands[it] }.associateBy { it["name"].asString() }
        val mounted = byName.getValue("ImportCart")
        mounted["mounted"].asBoolean().assert().isTrue()
        mounted["transformsTo"].strings().assert().containsExactly("$CONTEXT.cart.OpenCart")
        byName.getValue("Checkout")["routes"].strings("path").assert()
            .containsExactly("/shop/cart/{id}/checkout", "/shop/cart/checkout")
        byName.getValue("AddItem").has("mounted").assert().isFalse()
    }

    @Test
    fun `a command that creates nothing and is not unaddressed has no route without an id`() {
        HttpNode().post("/shop/cart/checkout", """{"note":"c-9"}""").status.assert().isEqualTo(404)
    }

    /** The strings of this array, or of each element's [field]. */
    private fun JsonNode.strings(field: String? = null): List<String> =
        (0 until size()).map { index -> (field?.let { this[index][it] } ?: this[index]).asString() }

    private fun transformer(input: CommandType, transform: (CommandInput) -> CommandTarget) =
        object : CommandTransformer {
            override val input: CommandType = input

            override suspend fun transform(input: CommandInput): CommandTarget = transform(input)
        }
}
