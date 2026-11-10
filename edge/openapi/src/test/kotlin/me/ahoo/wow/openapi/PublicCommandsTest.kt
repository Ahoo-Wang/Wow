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
import me.ahoo.wow.manifest.Creation
import me.ahoo.wow.manifest.FunctionKind
import me.ahoo.wow.manifest.FunctionManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.SubscriptionKind
import me.ahoo.wow.manifest.SubscriptionManifest
import me.ahoo.wow.manifest.SubscriptionSource
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.openapi.OpenApiFixtures.MANIFEST
import me.ahoo.wow.openapi.OpenApiFixtures.command
import me.ahoo.wow.openapi.OpenApiFixtures.schema
import org.junit.jupiter.api.Test

/** The commands the document shows ([PublicCommands]): an internal one is left out. */
class PublicCommandsTest {
    private val document = ManifestOpenApi.write(MANIFEST)

    @Test
    fun `an internal command should be left out, with the schemas only it reaches`() {
        val cart = MANIFEST.contexts.single().aggregates.first()
        val settle = command("Settle", Creation.NONE).copyWith(internal = true)
        val manifest = MANIFEST.copyWith(
            contexts = MANIFEST.contexts.map { context ->
                context.copyWith(
                    aggregates = listOf(cart.copyWith(commands = cart.commands + settle)) + context.aggregates.drop(1),
                    // A saga that derives the internal command refers to its schema too.
                    subscriptions = listOf(
                        SubscriptionManifest(
                            name = "Settling.onCartCreated",
                            kind = SubscriptionKind.SAGA,
                            source = SubscriptionSource("shop", listOf("cart")),
                            function = FunctionManifest(FunctionKind.SAGA, "shop", "Settling", "onCartCreated"),
                            derives = listOf(settle.schema),
                        ),
                    ),
                )
            },
            defs = MANIFEST.defs + mapOf(
                // The internal command's body, a type only it reaches, and one a public command reaches too.
                "sample.Settle" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "receipt" to mapOf("\$ref" to Manifest.schemaRef("sample.Receipt")),
                        "item" to mapOf("\$ref" to Manifest.schemaRef("sample.Item")),
                    ),
                ),
                "sample.Receipt" to mapOf("type" to "object"),
            ),
        )
        val written = ManifestOpenApi.write(manifest)
        OpenApiChecks.check(written)
        @Suppress("UNCHECKED_CAST")
        val paths = written["paths"] as Map<String, Any?>
        paths.keys.assert().doesNotContain("/shop/cart/{id}/settle")
        @Suppress("UNCHECKED_CAST")
        val schemas = (written["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>
        schemas.keys.assert().doesNotContain("sample.Settle", "sample.Receipt").contains("sample.Item")
        // Without it, the document is the one of the Manifest without the command.
        written.assert().isEqualTo(document)
    }
}
