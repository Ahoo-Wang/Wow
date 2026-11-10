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

import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

internal abstract class TagsMigrationFixtures {
    protected val order = NineXAggregate("example-service", "order")
    protected val region = AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING)
    protected val teams = AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true)
    protected val level = AccessAttributeManifest("level", AccessAttributeManifest.Type.INTEGER)
    protected val channel = AccessAttributeManifest("channel", AccessAttributeManifest.Type.STRING)

    protected val flag = AccessAttributeManifest("flag", AccessAttributeManifest.Type.BOOLEAN)
    protected val scores = AccessAttributeManifest("scores", AccessAttributeManifest.Type.INTEGER, multiple = true)

    protected val manifest: Manifest = manifestOf()

    /** The example's Manifest, its order declaring [access], and the tags event and command when [tagging]. */
    protected fun manifestOf(
        access: List<AccessAttributeManifest> = listOf(region, teams, level, channel),
        tagging: Boolean = true,
    ): Manifest = NineXExample.manifest.let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { aggregate ->
                        if (aggregate.name != "order") {
                            aggregate
                        } else {
                            aggregate.copyWith(
                                access = access,
                                events = aggregate.events + listOfNotNull(
                                    EventManifest("OrderTagged", schema = Manifest.schemaRef("example.OrderTagged"))
                                        .takeIf { tagging },
                                ),
                                commands = aggregate.commands + listOfNotNull(
                                    CommandManifest("TagOrder", schema = Manifest.schemaRef("example.TagOrder"))
                                        .takeIf { tagging },
                                ),
                            )
                        }
                    },
                )
            },
        )
    }

    protected val tags = listOf(
        TagMapping("region", "region"),
        TagMapping("team", "teams"),
        TagMapping("level", "level", TagSource.BOTH),
        TagMapping("channel", "channel", TagSource.DERIVED),
        TagMapping("legacy", reason = "No policy reads it: 9.x set it for a report retired in 2024."),
    )

    protected fun map(change: AggregateMapping.() -> AggregateMapping = { this }): MigrationMap =
        NineXExample.map.let { map ->
            map.copy(
                aggregates = map.aggregates.map { aggregate ->
                    if (aggregate.source() != order) {
                        aggregate
                    } else {
                        aggregate.copy(
                            tags = tags,
                            events = aggregate.events + EventMapping(MapDrafter.TAGS_EVENT, to = "OrderTagged", tags = true),
                            commands = aggregate.commands + CommandMapping(MapDrafter.TAGS_COMMAND, "TagOrder"),
                        ).change()
                    }
                },
            )
        }

    protected val streams = NineXExample.streams().filter { it.aggregate == order }

    /** The order's 9.x tags stream at [version], its event the 9.x tags event of [tags], a JSON object. */
    protected fun tagged(version: Int, tags: String, id: String = streams.first().aggregateId.id): NineXStream {
        val last = streams.last()
        return last.copy(
            id = "tags-$version-$id",
            aggregateId = last.aggregateId.copy(id = id),
            commandId = "tag-$version-$id",
            requestId = "tag-$version-$id",
            version = version,
            header = last.header + (NineXHeaders.UPSTREAM_NAME to MapDrafter.TAGS_COMMAND),
            events = listOf(NineXEvent("e-$version-$id", MapDrafter.TAGS_EVENT, "0.0.1", json("""{"tags": $tags}"""))),
            createTime = last.createTime + version,
        )
    }

    /** The order's 9.x snapshot at [version], holding [tags], a JSON object. */
    protected fun snapshot(version: Int, tags: String): NineXSnapshot {
        val node = json(NineXExample.resource("order-snapshot.json")) as ObjectNode
        node.put("version", version)
        node.set("tags", json(tags))
        return NineXSnapshot.parse(node)
    }

    protected fun json(text: String): JsonNode = WowJson.mapper.readTree(text)

    protected fun payloadOf(converter: StreamConverter, stream: NineXStream): JsonNode =
        json(converter.convert(stream).events.single().payload)

    /** The 9.x store of the example's order with its tags, its snapshot at [version] holding [snapshotTags]. */
    protected fun store(
        version: Int = 6,
        snapshotTags: String = SNAPSHOT_TAGS
    ): Pair<InMemoryNineXEventSource, InMemoryNineXSnapshotSource> {
        val source = InMemoryNineXEventSource(
            streams + tagged(5, """{"region": ["east"], "team": ["b", "a"], "level": ["3"], "legacy": ["x"]}""") +
                tagged(6, """{"region": ["east"], "team": ["a", "b"], "level": ["3"], "legacy": ["x"]}"""),
        )
        return source to InMemoryNineXSnapshotSource(listOf(snapshot(version, snapshotTags)), source)
    }

    protected companion object {
        /** The order's 9.x snapshot tags at version 6: as applied, the team values in another order, and derived. */
        const val SNAPSHOT_TAGS = """{"region": ["east"], "team": ["b", "a"], "level": ["1"], "channel": ["web"], "legacy": ["x"]}"""
    }
}

/** The V10 event that sets the order's tags, which 9.x's tags event maps to. */
internal data class OrderTagged(val region: String?, val teams: Set<String>)
