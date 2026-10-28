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

package me.ahoo.wow.query.mask

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The state and event payloads masked by their Manifest schemas, as a snapshot query masks its records (09 §17 G-2). */
class PayloadMasksTest {
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = ManifestFixtures.ORDER.context,
                aggregates = listOf(
                    AggregateManifest(
                        name = ManifestFixtures.ORDER.name,
                        state = StateManifest(Manifest.schemaRef(ManifestFixtures.ORDER_STATE)),
                        events = listOf(
                            EventManifest(
                                "OrderPlaced",
                                aliases = listOf("order_placed"),
                                schema = Manifest.schemaRef("OrderPlaced"),
                            ),
                            EventManifest("OrderNoted", schema = Manifest.schemaRef("OrderNoted")),
                            EventManifest("OrderLost", schema = Manifest.schemaRef("Missing")),
                        ),
                    ),
                ),
            ),
        ),
        defs = ManifestFixtures.ORDER_DEFS + mapOf(
            "OrderPlaced" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone" to mapOf(
                        "type" to "string",
                        "x-wow-sensitive" to mapOf("level" to "display"),
                        "x-wow-mask" to mapOf("keepPrefix" to 3, "keepSuffix" to 4),
                    ),
                    "sku" to mapOf("type" to "string"),
                ),
            ),
            "OrderNoted" to mapOf("type" to "object", "properties" to mapOf("note" to mapOf("type" to "string"))),
        ),
    )
    private val masks = PayloadMasks(manifest, ManifestFixtures.STRATEGIES)

    private fun tree(json: String) = WowJson.mapper.readTree(json)

    @Test
    fun `a state is masked by its schema, every sensitive level and a custom strategy`() {
        val state = tree(
            """{"password":"secret","phone":"13812345678","email":"a@b.c","address":{"city":"x","street":"main"},"note":"n"}"""
        )
        val masked = masks.state(ManifestFixtures.ORDER, state)
        masked["password"].asString().assert().isEqualTo("******")
        masked["phone"].asString().assert().isEqualTo("138****5678")
        masked["email"].asString().assert().isEqualTo("[redacted]")
        masked["address"]["street"].asString().assert().isEqualTo("****")
        masked["address"]["city"].asString().assert().isEqualTo("x")
        masked["note"].asString().assert().isEqualTo("n")
        // Twice: the compiled masker is reused.
        masks.state(
            ManifestFixtures.ORDER,
            tree("""{"password":"pw"}""")
        )["password"].asString().assert().isEqualTo("**")
    }

    @Test
    fun `an event body is masked by its event's schema, found by name or alias`() {
        masks.event(ManifestFixtures.ORDER, "OrderPlaced", tree("""{"phone":"13812345678","sku":"s"}"""))
            .toString().assert().isEqualTo("""{"phone":"138****5678","sku":"s"}""")
        masks.event(ManifestFixtures.ORDER, "order_placed", tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
        // The log's name of the event, qualified by its aggregate (decision D55), and a qualified alias.
        masks.event(ManifestFixtures.ORDER, "shop.order.OrderPlaced", tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
        masks.event(ManifestFixtures.ORDER, "shop.order.order_placed", tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
    }

    @Test
    fun `a payload without a sensitive schema is returned as it is`() {
        val body = tree("""{"phone":"13812345678"}""")
        masks.event(ManifestFixtures.ORDER, "OrderNoted", body).assert().isSameAs(body)
        masks.event(ManifestFixtures.ORDER, "OrderLost", body).assert().isSameAs(body)
        masks.event(ManifestFixtures.ORDER, "Unknown", body).assert().isSameAs(body)
        masks.state(AggregateType("shop", "other"), body).assert().isSameAs(body)
        PayloadMasks.NONE.state(ManifestFixtures.ORDER, body).assert().isSameAs(body)
    }

    @Test
    fun `a state the schema does not fit fails rather than return it raw`() {
        assertThrows<QueryExecutionException> {
            masks.state(ManifestFixtures.ORDER, tree("""{"password":{"raw":"secret"}}"""))
        }
    }
}
