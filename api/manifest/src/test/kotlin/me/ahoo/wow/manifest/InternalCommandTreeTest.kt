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

package me.ahoo.wow.manifest

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper

/** An internal command (`@InternalCommand`) in the Manifest's JSON: `internal` written when true, and read back. */
class InternalCommandTreeTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `an internal command should come back through its JSON text, and a public one leave internal out`() {
        val confirm = CommandManifest(
            name = "ConfirmPayment",
            schema = Manifest.schemaRef("sample.ConfirmPayment"),
            routes = CommandRoutes.of("shop", "payment", "ConfirmPayment", Creation.NONE),
            internal = true,
        )
        val manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            "payment",
                            state = StateManifest(Manifest.schemaRef("sample.Payment")),
                            commands = listOf(confirm, CommandManifest("Refund", schema = "#/\$defs/R")),
                        ),
                    ),
                ),
            ),
        )
        val json = mapper.writeValueAsString(ManifestTree.write(manifest))
        json.assert().contains("\"internal\":true")
        json.indexOf("\"internal\"").assert().isEqualTo(json.lastIndexOf("\"internal\""))
        ManifestTree.read(
            mapper.readValue(json, Map::class.java),
            ManifestTree.REFUSE
        ).contexts.single().aggregates.single().commands
            .assert().containsExactly(confirm, CommandManifest("Refund", schema = "#/\$defs/R"))
    }
}
