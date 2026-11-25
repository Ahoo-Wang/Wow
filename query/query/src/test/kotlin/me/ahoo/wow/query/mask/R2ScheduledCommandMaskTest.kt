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
import me.ahoo.wow.api.schedule.Schedules
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test

/**
 * Round two of the deep review, security: a schedule's event, `wow.schedule.ScheduleSet`, holds the body of the
 * command it will send (`ScheduleSet.command`). Its masks are the event's schema, and a system event has none in the
 * aggregate's Manifest, so [PayloadMasks.event] and [PayloadMasks.eventMaskOf] return the body as it is: a `@Sensitive`
 * value of the scheduled command is answered raw by a state trace and an events query and published raw on the state
 * topic, where the same value in a state or a domain event is masked (`guide/queries.md`: each `@Sensitive` value of
 * every event's body is replaced).
 */
class R2ScheduledCommandMaskTest {
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                name = ManifestFixtures.ORDER.context,
                aggregates = listOf(
                    AggregateManifest(
                        name = ManifestFixtures.ORDER.name,
                        state = StateManifest(Manifest.schemaRef(ManifestFixtures.ORDER_STATE)),
                        commands = listOf(CommandManifest("RemindBuyer", schema = Manifest.schemaRef("RemindBuyer"))),
                    ),
                ),
            ),
        ),
        defs = ManifestFixtures.ORDER_DEFS + mapOf(
            "RemindBuyer" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone" to mapOf(
                        "type" to "string",
                        "x-wow-sensitive" to mapOf("level" to "confidential"),
                    ),
                ),
            ),
        ),
    )
    private val masks = PayloadMasks(manifest, ManifestFixtures.STRATEGIES)

    private val scheduleSet = WowJson.mapper.readTree(
        """
        {"key":"remind","rule":{"type":"after","millis":60000},
         "target":{"context":"shop","aggregate":"order","tenantId":"t","id":"o-1"},
         "commandType":"shop.order.RemindBuyer","command":{"phone":"13812345678"}}
        """.trimIndent(),
    )

    @Test
    fun `the command a schedule holds is masked as its command's schema says, on a read`() {
        val masked = masks.event(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, scheduleSet.deepCopy())
        masked["command"]["phone"].asString().assert().isNotEqualTo("13812345678")
    }

    @Test
    fun `the command a schedule holds is masked where it is published`() {
        val mask = masks.eventMaskOf(ManifestFixtures.ORDER, Schedules.SCHEDULE_SET, revealsDisplay = false)
        val published = mask?.invoke(scheduleSet.deepCopy()) ?: scheduleSet
        published["command"]["phone"].asString().assert().isNotEqualTo("13812345678")
    }
}
