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
import me.ahoo.wow.api.lifecycle.Lifecycle
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.manifest.copyWith
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
        masks.event(ManifestFixtures.ORDER, "OrderPlaced", 1, tree("""{"phone":"13812345678","sku":"s"}"""))
            .toString().assert().isEqualTo("""{"phone":"138****5678","sku":"s"}""")
        masks.event(ManifestFixtures.ORDER, "order_placed", 1, tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
        // The log's name of the event, qualified by its aggregate (decision D55), and a qualified alias.
        masks.event(ManifestFixtures.ORDER, "shop.order.OrderPlaced", 1, tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
        masks.event(ManifestFixtures.ORDER, "shop.order.order_placed", 1, tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
    }

    @Test
    fun `a read that reveals DISPLAY values should get them raw, CONFIDENTIAL ones masked, state and event alike`() {
        val state = masks.state(
            ManifestFixtures.ORDER,
            tree("""{"password":"secret","phone":"13812345678","email":"a@b.c","address":{"street":"main"}}"""),
            revealsDisplay = true,
        )
        state.toString().assert()
            .isEqualTo("""{"password":"******","phone":"13812345678","email":"a@b.c","address":{"street":"****"}}""")
        masks.event(ManifestFixtures.ORDER, "OrderPlaced", 1, tree("""{"phone":"13812345678","sku":"s"}"""), true)
            .toString().assert().isEqualTo("""{"phone":"13812345678","sku":"s"}""")
        // The masks a read that does not ask is given are compiled apart, and stay whole.
        masks.state(ManifestFixtures.ORDER, tree("""{"phone":"13812345678"}"""))["phone"].asString().assert()
            .isEqualTo("138****5678")
        masks.event(ManifestFixtures.ORDER, "OrderPlaced", 1, tree("""{"phone":"13812345678"}"""))["phone"]
            .asString().assert().isEqualTo("138****5678")
    }

    @Test
    fun `a payload without a sensitive schema is returned as it is`() {
        val body = tree("""{"phone":"13812345678"}""")
        masks.event(ManifestFixtures.ORDER, "OrderNoted", 1, body).assert().isSameAs(body)
        // An event the Manifest lists without carrying its schema declares nothing sensitive.
        masks.event(ManifestFixtures.ORDER, "OrderLost", 1, body).assert().isSameAs(body)
        masks.eventMaskOf(ManifestFixtures.ORDER, "OrderLost", revealsDisplay = false).assert().isNull()
        masks.state(AggregateType("shop", "other"), body).assert().isSameAs(body)
        PayloadMasks.NONE.state(ManifestFixtures.ORDER, body).assert().isSameAs(body)
        // Wow's own events, which every node knows, whatever the Manifest lists.
        masks.event(ManifestFixtures.ORDER, Lifecycle.OWNER_TRANSFERRED, 1, body).assert().isSameAs(body)
        masks.event(ManifestFixtures.ORDER, ScheduleJobs.JOB_TICKED, 1, body).assert().isSameAs(body)
        masks.eventMaskOf(ManifestFixtures.ORDER, Lifecycle.OWNER_TRANSFERRED, revealsDisplay = false).assert().isNull()
    }

    @Test
    fun `an event the Manifest does not know, by name or revision, is masked whole (D485)`() {
        val body = tree("""{"phone":"13812345678"}""")
        // A new name, a newer revision than the Manifest's, an aggregate type it does not have.
        masks.event(ManifestFixtures.ORDER, "OrderRefunded", 1, body).isNull.assert().isTrue()
        masks.event(ManifestFixtures.ORDER, "shop.order.OrderNoted", 2, body).isNull.assert().isTrue()
        masks.event(AggregateType("shop", "other"), "OrderPlaced", 1, body).isNull.assert().isTrue()
        // A revealing read is no exception, and the mask a writer takes is the same.
        masks.event(ManifestFixtures.ORDER, "OrderRefunded", 1, body, revealsDisplay = true).isNull.assert().isTrue()
        val published = masks.eventMaskOf(ManifestFixtures.ORDER, "OrderRefunded", revealsDisplay = true)!!
        published(body).isNull.assert().isTrue()
        // What masks nothing masks nothing it does not know either.
        PayloadMasks.NONE.event(ManifestFixtures.ORDER, "OrderRefunded", 1, body).assert().isSameAs(body)
        PayloadMasks.NONE.eventMaskOf(ManifestFixtures.ORDER, "OrderRefunded", revealsDisplay = false).assert().isNull()
    }

    @Test
    fun `the state mask a writer takes should be the state read's, and none for a type that masks nothing`() {
        val raw = """{"password":"secret","phone":"13812345678","email":"a@b.c","address":{"street":"main"}}"""
        val mask = masks.stateMaskOf(ManifestFixtures.ORDER, revealsDisplay = false)!!
        mask(tree(raw)).assert().isEqualTo(masks.state(ManifestFixtures.ORDER, tree(raw)))
        mask(tree(raw))["email"].asString().assert().isEqualTo("[redacted]")
        val revealing = masks.stateMaskOf(ManifestFixtures.ORDER, revealsDisplay = true)!!
        revealing(tree(raw)).assert().isEqualTo(masks.state(ManifestFixtures.ORDER, tree(raw), revealsDisplay = true))
        revealing(tree(raw))["phone"].asString().assert().isEqualTo("13812345678")
        masks.stateMaskOf(AggregateType("shop", "other"), revealsDisplay = false).assert().isNull()
        val unmarked = mapOf(ManifestFixtures.ORDER_STATE to mapOf("type" to "object"))
        val plain = manifest.copyWith(defs = manifest.defs + unmarked)
        PayloadMasks(plain).stateMaskOf(ManifestFixtures.ORDER, revealsDisplay = false).assert().isNull()
    }

    @Test
    fun `the event mask a writer takes should be the event read's, found by name or alias, none where nothing masks`() {
        val raw = """{"phone":"13812345678","sku":"s"}"""
        listOf("OrderPlaced", "order_placed", "shop.order.OrderPlaced", "shop.order.order_placed").forEach { name ->
            val mask = masks.eventMaskOf(ManifestFixtures.ORDER, name, revealsDisplay = false)!!
            mask(tree(raw)).assert().isEqualTo(masks.event(ManifestFixtures.ORDER, name, 1, tree(raw)))
            mask(tree(raw))["phone"].asString().assert().isEqualTo("138****5678")
        }
        // Its one sensitive value is DISPLAY: the masks of a read that reveals DISPLAY values mask nothing of it.
        masks.eventMaskOf(ManifestFixtures.ORDER, "shop.order.OrderPlaced", revealsDisplay = true).assert().isNull()
        masks.eventMaskOf(ManifestFixtures.ORDER, "OrderNoted", revealsDisplay = false).assert().isNull()
        PayloadMasks.NONE.eventMaskOf(ManifestFixtures.ORDER, "OrderPlaced", revealsDisplay = false).assert().isNull()
    }

    @Test
    fun `a state the schema does not fit fails rather than return it raw`() {
        assertThrows<QueryExecutionException> {
            masks.state(ManifestFixtures.ORDER, tree("""{"password":{"raw":"secret"}}"""))
        }
    }
}
