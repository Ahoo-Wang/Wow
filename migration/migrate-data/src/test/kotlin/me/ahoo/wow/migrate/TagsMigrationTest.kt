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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.migrate.example.NineXExample
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

internal class TagsMigrationTest : TagsMigrationFixtures() {
    @Test
    fun `the tags event writes each applied attribute, an absent key as none, as 9x replaced the whole map`() {
        val converter = StreamConverter(MigrationPlan(map(), manifest))
        // Derived (channel), applied and derived (level) and left out (legacy): none is written.
        payloadOf(
            converter,
            tagged(
                5,
                """{"region": ["east"], "team": ["b", "a", "b"], "level": ["3"], "channel": ["web"], "legacy": ["x"]}"""
            ),
        ).assert().isEqualTo(json("""{"region": "east", "teams": ["b", "a"]}"""))
        // A retag that leaves the region out clears it; an empty map clears every attribute.
        payloadOf(converter, tagged(6, """{"team": ["c"]}""")).assert()
            .isEqualTo(json("""{"region": null, "teams": ["c"]}"""))
        payloadOf(converter, tagged(7, "{}")).assert().isEqualTo(json("""{"region": null, "teams": []}"""))
        val failure = assertThrows<MigrationException> {
            converter.convert(
                tagged(8, """{"region": ["east", "west"]}""")
            )
        }
        failure.message!!.assert().contains(
            "the 9.x tag region holds 2 values, [east, west], which the single attribute region cannot.",
        )
    }

    @Test
    fun `the rollback export writes the attributes back as the 9x tags, an attribute without a value as no key`() {
        val plan = MigrationPlan(map(), manifest)
        val converter = StreamConverter(plan)
        val reverter = StreamReverter(plan) { _, _ -> listOf("0.0.1") }
        val record = converter.convert(tagged(5, """{"region": ["east"], "team": ["a", "b"], "level": ["3"]}"""))
        reverter.revert(record).events.single().run {
            name.assert().isEqualTo(MapDrafter.TAGS_EVENT)
            body.assert().isEqualTo(json("""{"tags": {"region": ["east"], "team": ["a", "b"]}}"""))
        }
        val cleared = converter.convert(tagged(6, "{}"))
        reverter.revert(cleared).events.single().body.assert().isEqualTo(json("""{"tags": {}}"""))
        val back = reverter.revert(record)
        converter.convert(back).events.assert().isEqualTo(record.events)
        TagValues.stringsOf(json("""[3, null, true]""")).assert().containsExactly("3", "true")
        TagValues.stringsOf(json("7")).assert().containsExactly("7")
        TagValues.stringsOf(null).assert().isEmpty()
    }

    @Test
    fun `the export gives a tags event's other fields their 9x names back`() {
        val plan = MigrationPlan(
            map {
                copy(
                    events = events.map {
                        if (it.tags) it.copy(fields = mapOf("note" to "remark")) else it
                    },
                )
            },
            manifest,
        )
        val stream = tagged(5, "{}").let { stream ->
            stream.copy(
                events = listOf(
                    stream.events.single().copy(body = json("""{"note": "n", "tags": {"region": ["east"]}}""")),
                ),
            )
        }
        val record = StreamConverter(plan).convert(stream)
        json(
            record.events.single().payload
        ).assert().isEqualTo(json("""{"remark": "n", "region": "east", "teams": []}"""))
        StreamReverter(plan) { _, _ -> listOf("0.0.1") }.revert(record).events.single().body.assert()
            .isEqualTo(json("""{"note": "n", "tags": {"region": ["east"]}}"""))
    }

    @Test
    fun `a mapping refuses a blank key or attribute, and a tags event it drops or retires`() {
        assertThrows<IllegalArgumentException> { TagMapping(" ", "region") }
        assertThrows<IllegalArgumentException> { TagMapping("region", " ") }
        assertThrows<IllegalArgumentException> { EventMapping(MapDrafter.TAGS_EVENT, drop = true, tags = true) }
        assertThrows<IllegalArgumentException> {
            EventMapping(MapDrafter.TAGS_EVENT, to = "OrderTagged", retire = true, tags = true)
        }
        TagReader.NONE.samples(order, "region", TagValueProblem.MULTIPLE, fromSnapshots = false).assert().isEmpty()
        TagValues.booleanOf("FALSE").assert().isEqualTo(false)
        TagValues.problems(flag, TagValueStats(count = 2, notInteger = 2, notBoolean = 1)).assert()
            .isEqualTo(mapOf(TagValueProblem.NOT_BOOLEAN to 1L))
    }

    @Test
    fun `the walks stop at the samples a check names`() {
        val (source, _) = store()
        source.add(tagged(1, """{"region": ["east", "west"]}""", id = "order-2"))
        source.add(tagged(1, """{"region": ["north", "south"]}""", id = "order-3"))
        source.tagSamples(order, "region", TagValueProblem.MULTIPLE, 1).assert().containsExactly("tags-1-order-2")
        val snapshots = InMemoryNineXSnapshotSource(
            listOf("a", "b").map { id ->
                val node = json(NineXExample.resource("order-snapshot.json")) as ObjectNode
                node.put("aggregateId", "order-$id")
                node.set("tags", json("""{"region": ["east", "west"]}"""))
                NineXSnapshot.parse(node)
            },
            source,
        )
        snapshots.tagSamples(order, "region", TagValueProblem.MULTIPLE, 1).assert().containsExactly("order-a")
    }

    @Test
    fun `an event of the user's that holds tags keeps its other fields, renamed`() {
        val labelled = EventMapping(
            "order_labelled",
            to = "OrderTagged",
            tags = true,
            fields = mapOf("note" to "remark")
        )
        val converter = StreamConverter(MigrationPlan(map { copy(events = events + labelled) }, manifest))
        val stream = tagged(5, "{}").let { stream ->
            stream.copy(
                events = listOf(
                    stream.events.single().copy(
                        name = "order_labelled",
                        body = json("""{"note": "n", "tags": {"region": ["east"]}}""")
                    )
                )
            )
        }
        payloadOf(converter, stream).assert().isEqualTo(json("""{"remark": "n", "region": "east", "teams": []}"""))
        // An attribute the V10 aggregate does not declare fails the stream, which the check names first.
        val undeclared = StreamConverter(
            MigrationPlan(map { copy(tags = listOf(TagMapping("region", "zone"))) }, manifest)
        )
        assertThrows<MigrationException> { undeclared.convert(tagged(5, "{}")) }.message!!.assert()
            .contains("the 9.x tag region maps to zone, which example.order does not declare as an access attribute.")
    }

    @Test
    fun `values convert by the attribute's Manifest entry`() {
        TagValues.valueOf(teams, listOf("b", "a", "b")).assert().isEqualTo(listOf("b", "a"))
        TagValues.valueOf(teams, null).assert().isEqualTo(emptyList<String>())
        TagValues.valueOf(region, emptyList()).assert().isNull()
        TagValues.valueOf(level, listOf("+3")).assert().isEqualTo(3L)
        val flag = AccessAttributeManifest("flag", AccessAttributeManifest.Type.BOOLEAN)
        TagValues.valueOf(flag, listOf("TRUE")).assert().isEqualTo(true)
        assertThrows<TagValueException> { TagValues.valueOf(level, listOf("three")) }.message.assert()
            .isEqualTo("holds three, which the integer attribute level cannot")
        assertThrows<TagValueException> { TagValues.valueOf(flag, listOf("yes")) }
        TagValueStats.of(
            listOf("a", "1")
        ).assert().isEqualTo(TagValueStats(1, multiple = 1, notInteger = 1, notBoolean = 1))
        TagValues.problems(level, TagValueStats(3, multiple = 1, notInteger = 2, notBoolean = 3)).assert()
            .isEqualTo(mapOf(TagValueProblem.MULTIPLE to 1L, TagValueProblem.NOT_INTEGER to 2L))
        NineXTags.of(json("""{"a": ["x", null], "b": "y", "c": null}""")).assert()
            .isEqualTo(mapOf("a" to listOf("x"), "b" to listOf("y"), "c" to emptyList()))
        NineXTags.of(json("[]")).assert().isNull()
    }

    @Test
    fun `the map reads its tags, and refuses a key twice, an attribute twice, and a key left out without a reason`() {
        val text = NineXExample.MAP_TEXT.replace(
            "\"to\": \"example.order\",",
            """"to": "example.order", "tags": [{ "key": "dept", "to": "department" }, """ +
                """{ "key": "org", "to": "org", "source": "DERIVED" }, { "key": "old", "reason": "Unused." }],""",
        )
        MigrationMap.read(text).aggregates.first { it.source() == order }.tags.assert().containsExactly(
            TagMapping("dept", "department"),
            TagMapping("org", "org", TagSource.DERIVED),
            TagMapping("old", reason = "Unused."),
        )
        assertThrows<IllegalArgumentException> { TagMapping("old") }
        assertThrows<IllegalArgumentException> {
            map {
                copy(
                    tags = listOf(TagMapping("a", "x"), TagMapping("a", "y"))
                )
            }
        }
        assertThrows<IllegalArgumentException> {
            map {
                copy(
                    tags = listOf(TagMapping("a", "x"), TagMapping("b", "x"))
                )
            }
        }
        assertThrows<IllegalArgumentException> { EventMapping("tags", to = null, drop = true, tags = true) }
    }
}
