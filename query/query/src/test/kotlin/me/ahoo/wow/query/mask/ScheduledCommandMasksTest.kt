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
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

/**
 * A schedule's event, `wow.schedule.ScheduleSet`, holds the body of the command it will send: masked as a body of its
 * command type is (#7066), by its schema in the node's Manifest or, for a command of another context, in a peer's
 * contract, wherever the event is read ([PayloadMasks.event]: a state trace, an events query) or published
 * ([PayloadMasks.eventMaskOf]: the state topic).
 */
class ScheduledCommandMasksTest {
    private fun sensitive(level: String) = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "phone" to mapOf("type" to "string", "x-wow-sensitive" to mapOf("level" to level)),
            "note" to mapOf("type" to "string"),
        ),
    )

    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = ManifestFixtures.ORDER.context,
                aggregates = listOf(
                    AggregateManifest(
                        name = ManifestFixtures.ORDER.name,
                        state = StateManifest(Manifest.schemaRef(ManifestFixtures.ORDER_STATE)),
                        commands = listOf(
                            CommandManifest(
                                "RemindBuyer",
                                aliases = listOf("Nudge", "shop.order.Poke", "Ping"),
                                schema = Manifest.schemaRef("RemindBuyer"),
                            ),
                            CommandManifest("TextBuyer", schema = Manifest.schemaRef("TextBuyer")),
                            CommandManifest("Ghost", schema = Manifest.schemaRef("Missing")),
                        ),
                    ),
                ),
            ),
        ),
        defs = ManifestFixtures.ORDER_DEFS + mapOf(
            "RemindBuyer" to sensitive("confidential"),
            "TextBuyer" to sensitive("display"),
        ),
    )

    /** The contract of a peer context the node schedules commands to. */
    private val peer = Manifest(
        contexts = listOf(
            ContextManifest(
                name = "notice",
                aggregates = listOf(
                    AggregateManifest(
                        name = "letter",
                        state = StateManifest(Manifest.schemaRef("Letter")),
                        commands = listOf(CommandManifest("SendLetter", schema = Manifest.schemaRef("SendLetter"))),
                    ),
                ),
            ),
        ),
        defs = mapOf("Letter" to mapOf("type" to "object"), "SendLetter" to sensitive("confidential")),
    )

    private val masks = PayloadMasks(manifest, ManifestFixtures.STRATEGIES, listOf(peer))

    private fun scheduleSet(commandType: String, target: AggregateType = ManifestFixtures.ORDER): JsonNode =
        WowJson.mapper.readTree(
            """
            {"key":"remind","rule":{"type":"after","millis":60000},
             "target":{"context":"${target.context}","aggregate":"${target.name}","tenantId":"t","id":"o-1"},
             "commandType":"$commandType","command":{"phone":"13812345678","note":"call"}}
            """.trimIndent(),
        )

    private fun read(body: JsonNode, revealsDisplay: Boolean = false): JsonNode =
        masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, body, revealsDisplay)["command"]

    @Test
    fun `the command a schedule holds is masked as its command's schema says, on a read`() {
        val command = read(scheduleSet("shop.order.RemindBuyer"))
        command["phone"].asString().assert().isNotEqualTo("13812345678")
        command["note"].asString().assert().isEqualTo("call")
    }

    @Test
    fun `the command a schedule holds is masked where it is published`() {
        val mask = masks.eventMaskOf(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, revealsDisplay = false)
        checkNotNull(mask)(scheduleSet("shop.order.RemindBuyer"))["command"]["phone"].asString().assert()
            .isNotEqualTo("13812345678")
    }

    @Test
    fun `a command of another context is masked by its peer's contract`() {
        val command = read(scheduleSet("notice.letter.SendLetter", AggregateType("notice", "letter")))
        command["phone"].asString().assert().isNotEqualTo("13812345678")
    }

    @Test
    fun `a command recorded under a former name is masked as the command it names`() {
        read(scheduleSet("shop.order.Nudge"))["phone"].asString().assert().isNotEqualTo("13812345678")
        // A former name the Manifest lists in full, and one recorded without its context and aggregate.
        read(scheduleSet("shop.order.Poke"))["phone"].asString().assert().isNotEqualTo("13812345678")
        read(scheduleSet("Ping"))["phone"].asString().assert().isNotEqualTo("13812345678")
    }

    @Test
    fun `a read that reveals DISPLAY values masks the CONFIDENTIAL ones of a scheduled command alone`() {
        read(scheduleSet("shop.order.TextBuyer"), revealsDisplay = true)["phone"].asString().assert()
            .isEqualTo("13812345678")
        read(scheduleSet("shop.order.TextBuyer"))["phone"].asString().assert().isNotEqualTo("13812345678")
        read(scheduleSet("shop.order.RemindBuyer"), revealsDisplay = true)["phone"].asString().assert()
            .isNotEqualTo("13812345678")
    }

    @Test
    fun `a command type no Manifest names, or a schedule without a command, is left as it is`() {
        read(scheduleSet("shop.order.Unknown"))["phone"].asString().assert().isEqualTo("13812345678")
        read(scheduleSet("RemindBuyer"))["phone"].asString().assert().isEqualTo("13812345678")
        // A command whose schema the Manifest does not carry.
        read(scheduleSet("shop.order.Ghost"))["phone"].asString().assert().isEqualTo("13812345678")
        val text = WowJson.mapper.readTree("\"not an event\"")
        masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, text).assert().isEqualTo(text)
        val scalar = WowJson.mapper.readTree("""{"commandType":"shop.order.RemindBuyer","command":"13812345678"}""")
        masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, scalar).assert().isEqualTo(scalar)
        val numbered = WowJson.mapper.readTree("""{"commandType":7,"command":{"phone":"13812345678"}}""")
        masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, numbered)["command"]["phone"].asString().assert()
            .isEqualTo("13812345678")
        val noCommand = WowJson.mapper.readTree("""{"key":"k","commandType":"shop.order.RemindBuyer"}""")
        masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, noCommand).assert().isEqualTo(noCommand)
        val unnamed = WowJson.mapper.readTree("""{"key":"k","command":{"phone":"13812345678"}}""")
        masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, unnamed)["command"]["phone"].asString().assert()
            .isEqualTo("13812345678")
    }
}
