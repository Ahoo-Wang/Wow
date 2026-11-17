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

package me.ahoo.wow.migrate

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** A tags event's payload field and an attribute its tags are written as, which would overwrite one another. */
internal class TagsCollisionTest : TagsMigrationFixtures() {
    private fun labelled(fields: Map<String, String> = emptyMap()) =
        EventMapping("order_labelled", to = "OrderTagged", tags = true, fields = fields)

    private fun converter(fields: Map<String, String> = emptyMap()) =
        StreamConverter(MigrationPlan(map { copy(events = events + labelled(fields)) }, manifest))

    /** The order's 9.x stream at [version] holding the user's tags event `order_labelled` of [body]. */
    private fun stream(body: String, version: Int = 5, name: String = "order_labelled"): NineXStream =
        tagged(version, "{}").let { stream ->
            stream.copy(events = listOf(stream.events.single().copy(name = name, body = json(body))))
        }

    @Test
    fun `a rename onto an attribute the tags are written as is refused, and one onto an attribute not written is not`() {
        val failure = assertThrows<IllegalArgumentException> {
            map { copy(events = events + labelled(mapOf("note" to "region", "memo" to "teams"))) }
        }
        failure.message.assert().isEqualTo(
            "example-service.order: the tags event order_labelled renames 9.x fields to the attributes its tags " +
                "are written as: memo to teams, note to region.",
        )
        // Level is derived too and channel only derived, so the tags event writes neither.
        val payload = payloadOf(
            converter(mapOf("note" to "level", "memo" to "channel")),
            stream("""{"note": 3, "memo": "m", "tags": {"region": ["east"]}}"""),
        )
        payload.assert().isEqualTo(json("""{"level": 3, "channel": "m", "region": "east", "teams": []}"""))
    }

    @Test
    fun `a payload field held as an attribute the tags are written as fails the stream, with the tag or without`() {
        val converter = converter()
        val written = assertThrows<MigrationException> {
            converter.convert(stream("""{"region": "ordinary", "tags": {"region": ["east"]}}"""))
        }
        written.message.assert().endsWith(
            "event 0 (order_labelled): the 9.x field region and the 9.x tag region both become region, and one " +
                "would overwrite the other.",
        )
        // An absent tag is written as none, so it would erase the field just the same.
        assertThrows<MigrationException> { converter.convert(stream("""{"teams": ["x"], "tags": {}}""")) }
            .message.assert().contains("the 9.x field teams and the 9.x tag team both become teams")
        // 9.x's own tags event too.
        assertThrows<MigrationException> {
            converter.convert(stream("""{"region": "r", "tags": {}}""", name = MapDrafter.TAGS_EVENT))
        }.message.assert().contains("(${MapDrafter.TAGS_EVENT}): the 9.x field region and the 9.x tag region")
        // A field renamed away from the attribute's name converts.
        payloadOf(converter(mapOf("region" to "origin")), stream("""{"region": "r", "tags": {"region": ["east"]}}"""))
            .assert().isEqualTo(json("""{"origin": "r", "region": "east", "teams": []}"""))
    }

    @Test
    fun `the decode check reports a stream whose field a tag would overwrite as not converting`() = runTest {
        val plan = MigrationPlan(map { copy(events = events + labelled()) }, manifest)
        val colliding = stream("""{"region": "ordinary", "tags": {"region": ["east"]}}""")
        val reported = mutableListOf<DecodeFailure>()
        val models = MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel))
        DecodeCheck(InMemoryNineXEventSource(streams + colliding), plan, models, report = { reported += it })
            .check(order).also {
                it.streams.assert().isEqualTo(5)
                it.unconverted.assert().isEqualTo(1)
                it.passed.assert().isFalse()
            }
        reported.single().reason.assert().contains("both become region")
    }
}
