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
import org.junit.jupiter.api.Test

internal class TagsCheckTest : TagsMigrationFixtures() {
    private fun check(
        map: MigrationMap,
        source: InMemoryNineXEventSource,
        snapshots: NineXSnapshotSource
    ): MappingReport =
        MappingCheck(MigrationPlan(map, manifest)).check(source.inventory(), tags = TagReader.of(source, snapshots))

    @Test
    fun `the check passes the mapped tags, quoting why a key is left out and warning of one applied and derived`() {
        val (source, snapshots) = store()
        val report = check(map(), source, snapshots)
        report.problems.assert().isEmpty()
        report.warnings.assert().contains(
            "$order: the 9.x tag key legacy (2 tags events and 1 snapshots) is left out: No policy reads it: 9.x set " +
                "it for a report retired in 2024.",
            "$order: the 9.x tag key level is applied and derived: V10 derives level with an @Access function, and " +
                "the values 2 tags events wrote are left out of their events",
        )
    }

    @Test
    fun `the check refuses a key it does not map, one mapped as the wrong source, and an undeclared attribute`() {
        val (source, snapshots) = store(snapshotTags = """{"region": ["east"], "zone": ["z"]}""")
        val report = check(
            map {
                copy(
                    tags = listOf(
                        TagMapping("region", "region", TagSource.DERIVED),
                        TagMapping("team", "teams"),
                        TagMapping("level", "level"),
                        TagMapping("legacy", "legacy"),
                    ),
                )
            },
            source,
            snapshots,
        )
        report.problems.assert().containsExactlyInAnyOrder(
            "$order: the 9.x tag key zone (1 snapshots) has no tag mapping: map it to the V10 access attribute it " +
                "becomes (source DERIVED), or leave it out with a reason",
            "$order: the 9.x tag key region is mapped as DERIVED, but 2 tags events wrote it: map it as BOTH (V10 " +
                "derives it, and leaves the values they wrote out) or APPLIED",
            "$order: the 9.x tag key legacy maps to legacy, which example.order does not declare as an access " +
                "attribute (@Access)",
        )
        // A key only the snapshots hold, mapped as applied, was derived by the 9.x state.
        check(
            map {
                copy(
                    tags = tags.map {
                        if (it.key == "channel") it.copy(source = TagSource.APPLIED) else it
                    }
                )
            },
            store().first,
            store().second
        )
            .problems.assert().containsExactly(
                "$order: the 9.x tag key channel is mapped as APPLIED, but no tags event wrote it: the 1 snapshots that " +
                    "hold it had it derived by the 9.x state, so map it as DERIVED",
            )
    }

    @Test
    fun `the check refuses values the import cannot write, naming streams that hold them, warns of a derived key's, and refuses a tags event without tags`() {
        val (source, snapshots) = store()
        source.add(
            tagged(1, """{"region": ["east", "west"], "team": [], "level": ["3"], "legacy": ["x"]}""", id = "order-2")
        )
        source.add(
            tagged(1, """{"region": ["north", "south"], "team": [], "level": ["3"], "legacy": ["x"]}""", id = "order-3")
        )
        val report = check(map(), source, snapshots)
        report.problems.assert().containsExactly(
            "$order: the 9.x tag key region, mapped to the single string attribute region, holds two values or more " +
                "in 2 tags events (streams tags-1-order-2, tags-1-order-3)",
        )
        // 9.x merged what the state derived into the tags it kept, so a derived key's snapshot holds its former values too.
        val (merged, held) = store(
            snapshotTags = """{"region": ["east"], "team": ["a", "b"], "level": ["3", "x"], "channel": ["web", "app"]}"""
        )
        check(map(), merged, held).run {
            problems.assert().isEmpty()
            warnings.assert().contains(
                "$order: the 9.x tag key level, mapped to the single integer attribute level, holds a value that is no " +
                    "integer in 1 snapshots (instances 0VY3GwaK4OmV00E): V10 derives level from the state, so the import " +
                    "writes none of them, and compare reports these instances as \$access.level differences",
                "$order: the 9.x tag key channel, mapped to the single string attribute channel, holds two values or " +
                    "more in 1 snapshots (instances 0VY3GwaK4OmV00E): V10 derives channel from the state, so the " +
                    "import writes none of them, and compare reports these instances as \$access.channel differences",
            )
        }
        // A key applied and derived that only the snapshots hold is no warning; a key nothing holds is.
        check(
            map {
                copy(
                    tags = tags.map { if (it.key == "channel") it.copy(source = TagSource.BOTH) else it } + TagMapping("gone", reason = "r")
                )
            },
            store().first,
            store().second,
        ).warnings.run {
            none { "channel is applied and derived" in it }.assert().isTrue()
            assert().contains("$order: no tags event or snapshot holds the 9.x tag key gone")
        }
        val untagged = map {
            copy(
                events = events.map { if (it.from == MapDrafter.TAGS_EVENT) it.copy(tags = false) else it }
            )
        }
        check(untagged, store().first, store().second).problems.assert().contains(
            "$order: the 9.x tags event ${MapDrafter.TAGS_EVENT} (2 events) is mapped without \"tags\": true, so the " +
                "tags it wrote would not reach the V10 attributes",
        )
    }

    @Test
    fun `the check refuses a dropped tags event, an applied key it does not map and values of other types, and warns of a user's event with tags`() {
        val (source, snapshots) = store()
        source.add(
            tagged(7, """{"extra": ["x"], "flag": ["yes"], "score": ["1", "x"]}""").let { stream ->
                stream.copy(events = listOf(stream.events.single().copy(name = "order_labelled")))
            },
        )
        val typed = manifestOf(listOf(region, teams, level, channel, flag, scores))
        val dropped = map {
            copy(
                events = events.map { if (it.tags) EventMapping(it.from, drop = true) else it } +
                    EventMapping("order_labelled", to = "OrderTagged"),
                tags = tags + TagMapping("flag", "flag") + TagMapping("score", "scores"),
            )
        }
        // A store that names no sample of a problem.
        val unsampled = object : TagReader by TagReader.of(source, snapshots) {
            override fun samples(
                aggregate: NineXAggregate,
                key: String,
                problem: TagValueProblem,
                fromSnapshots: Boolean
            ) =
                emptyList<String>()
        }
        val report = MappingCheck(MigrationPlan(dropped, typed)).check(source.inventory(), tags = unsampled)
        report.problems.assert().contains(
            "$order: the 9.x event ${MapDrafter.TAGS_EVENT} (2 events) writes tags and is dropped, so the attributes " +
                "[region, teams, flag, scores] would be lost: map it to the V10 event that sets them, with \"tags\": true",
            "$order: the 9.x tag key extra (1 tags events) has no tag mapping: map it to the V10 access attribute it " +
                "becomes (source APPLIED), or leave it out with a reason",
            "$order: the 9.x tag key flag, mapped to the single boolean attribute flag, holds a value that is not true " +
                "or false in 1 tags events",
            "$order: the 9.x tag key score, mapped to the multiple integer attribute scores, holds a value that is no " +
                "integer in 1 tags events",
        )
        report.warnings.assert().contains(
            "$order: the 9.x event order_labelled (1 events) holds a tags object: if it implements " +
                "ResourceTagsApplied, map it with \"tags\": true",
        )
    }

    @Test
    fun `draft-map names the tags event and command to define, and reviews another event that holds tags`() {
        val (source, snapshots) = store()
        source.add(
            tagged(7, """{"region": ["east"]}""").let { stream ->
                stream.copy(events = listOf(stream.events.single().copy(name = "address_changed")))
            },
        )
        val draft = MapDrafter(manifestOf(tagging = false))
            .draft(source.inventory(), emptyMap(), TagReader.of(source, snapshots)::tags)
        val mapping = draft.aggregates.first { it.source() == order }
        mapping.events.first { it.from == MapDrafter.TAGS_EVENT }.to.assert().isEqualTo("OrderTagged")
        mapping.commands.assert().contains(CommandMapping(MapDrafter.TAGS_COMMAND, "TagOrder"))
        draft.exceptions.assert().contains(
            "${MapDrafter.REVIEW} $order event ${MapDrafter.TAGS_EVENT}: 9.x's tags; define the V10 event OrderTagged " +
                "that sets the access attributes the tags table maps (one field each, named as the attribute), or name yours",
            "${MapDrafter.REVIEW} $order command ${MapDrafter.TAGS_COMMAND}: 9.x's tags command; define the V10 command " +
                "TagOrder that emits the tags event, or name yours",
            "${MapDrafter.REVIEW} $order event address_changed: it holds a tags object; if it implements " +
                "ResourceTagsApplied, set \"tags\": true on its mapping",
        )
        val events = listOf(
            EventMapping("order_archived", to = "OrderArchived", retire = true),
            EventMapping(
                "owner_transferred",
                to = "wow.lifecycle.OwnerTransferred",
                fields = mapOf("toOwnerId" to "ownerId")
            ),
        )
        MapDraft(listOf(mapping.copy(events = events)), emptyList()).render().assert()
            .contains("\"retire\" : true").contains("\"toOwnerId\" : \"ownerId\"")
    }

    @Test
    fun `draft-map maps the tags event and command, and drafts the tags table by where each key came from`() {
        val (source, snapshots) = store()
        val draft = MapDrafter(manifest).draft(source.inventory(), emptyMap(), TagReader.of(source, snapshots)::tags)
        val mapping = draft.aggregates.first { it.source() == order }
        mapping.events.first { it.from == MapDrafter.TAGS_EVENT }.assert()
            .isEqualTo(EventMapping(MapDrafter.TAGS_EVENT, to = "OrderTagged", tags = true))
        mapping.commands.assert().contains(CommandMapping(MapDrafter.TAGS_COMMAND, "TagOrder"))
        mapping.tags.assert().containsExactly(
            TagMapping("channel", "channel", TagSource.DERIVED),
            TagMapping("legacy", "legacy"),
            TagMapping("level", "level"),
            TagMapping("region", "region"),
            TagMapping("team", "team"),
        )
        draft.exceptions.assert().contains(
            "${MapDrafter.UNMATCHED} $order tag key team (APPLIED): no access attribute of example.order matches it; " +
                "declare one (@Access), name it here, or leave the key out with a reason",
        )
        draft.render().assert().contains(
            "\"tags\" : true"
        ).contains("\"key\" : \"channel\"").contains("\"source\" : \"DERIVED\"")
        listOf("TagOrder", "OrderTagged", "TagsAssigned", "example.order.OrderTagged").forEach {
            MapDrafter.tagging(it).assert().isTrue()
        }
        listOf("OrderStaged", "PostageApplied", "OrderCreated").forEach { MapDrafter.tagging(it).assert().isFalse() }
    }
}
