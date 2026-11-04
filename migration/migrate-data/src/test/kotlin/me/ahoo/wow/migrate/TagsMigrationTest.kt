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
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.manifest.AccessAttributeManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.copyWith
import me.ahoo.wow.migrate.example.AddressChanged
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.migrate.example.OrderCreated
import me.ahoo.wow.migrate.example.OrderItem
import me.ahoo.wow.migrate.example.OrderPaid
import me.ahoo.wow.migrate.example.OrderReceived
import me.ahoo.wow.migrate.example.OrderShipped
import me.ahoo.wow.migrate.example.OrderStatus
import me.ahoo.wow.migrate.example.ShippingAddress
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * 9.x tags as V10 access attributes (D390 work package F), on the example's order: `region` a single attribute and
 * `team` a multiple one (`teams`), both applied; `channel` derived by the 9.x state; `level` applied and derived, the
 * extractor having the last word; `legacy` left out for a reason.
 */
class TagsMigrationTest {
    private val order = NineXAggregate("example-service", "order")
    private val region = AccessAttributeManifest("region", AccessAttributeManifest.Type.STRING)
    private val teams = AccessAttributeManifest("teams", AccessAttributeManifest.Type.STRING, multiple = true)
    private val level = AccessAttributeManifest("level", AccessAttributeManifest.Type.INTEGER)
    private val channel = AccessAttributeManifest("channel", AccessAttributeManifest.Type.STRING)

    private val manifest: Manifest = NineXExample.manifest.let { manifest ->
        manifest.copyWith(
            contexts = manifest.contexts.map { context ->
                context.copyWith(
                    aggregates = context.aggregates.map { aggregate ->
                        if (aggregate.name != "order") {
                            aggregate
                        } else {
                            aggregate.copyWith(
                                access = listOf(region, teams, level, channel),
                                events = aggregate.events +
                                    EventManifest("OrderTagged", schema = Manifest.schemaRef("example.OrderTagged")),
                                commands = aggregate.commands +
                                    CommandManifest("TagOrder", schema = Manifest.schemaRef("example.TagOrder")),
                            )
                        }
                    },
                )
            },
        )
    }

    private val tags = listOf(
        TagMapping("region", "region"),
        TagMapping("team", "teams"),
        TagMapping("level", "level", TagSource.BOTH),
        TagMapping("channel", "channel", TagSource.DERIVED),
        TagMapping("legacy", reason = "No policy reads it: 9.x set it for a report retired in 2024."),
    )

    private fun map(change: AggregateMapping.() -> AggregateMapping = { this }): MigrationMap =
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

    private val streams = NineXExample.streams().filter { it.aggregate == order }

    /** The order's 9.x tags stream at [version], its event the 9.x tags event of [tags], a JSON object. */
    private fun tagged(version: Int, tags: String, id: String = streams.first().aggregateId.id): NineXStream {
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
    private fun snapshot(version: Int, tags: String): NineXSnapshot {
        val node = json(NineXExample.resource("order-snapshot.json")) as ObjectNode
        node.put("version", version)
        node.set("tags", json(tags))
        return NineXSnapshot.parse(node)
    }

    private fun json(text: String): JsonNode = WowJson.mapper.readTree(text)

    private fun payloadOf(converter: StreamConverter, stream: NineXStream): JsonNode =
        json(converter.convert(stream).events.single().payload)

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

    /** The 9.x store of the example's order with its tags, its snapshot at [version] holding [snapshotTags]. */
    private fun store(
        version: Int = 6,
        snapshotTags: String = SNAPSHOT_TAGS
    ): Pair<InMemoryNineXEventSource, InMemoryNineXSnapshotSource> {
        val source = InMemoryNineXEventSource(
            streams + tagged(5, """{"region": ["east"], "team": ["b", "a"], "level": ["3"], "legacy": ["x"]}""") +
                tagged(6, """{"region": ["east"], "team": ["a", "b"], "level": ["3"], "legacy": ["x"]}"""),
        )
        return source to InMemoryNineXSnapshotSource(listOf(snapshot(version, snapshotTags)), source)
    }

    private fun check(map: MigrationMap, source: InMemoryNineXEventSource, snapshots: NineXSnapshotSource): MappingReport =
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

    @Test
    fun `compare compares the mapped tags with the access attributes V10 derives, values in any order`() = runTest {
        val (source, _) = store()
        val plan = MigrationPlan(map(), manifest)
        val log = InMemoryEventLog(4)
        val checkpoints = InMemoryCheckpointRepository()
        EventStreamImporter(source, plan, { log }, checkpoints).importAll(order).imported.assert().isEqualTo(6)
        val models = MigrationModels(listOf(taggedOrderModel, NineXExample.cartModel))
        val printed = mutableListOf<String>()
        fun comparison(snapshot: NineXSnapshot) = StateComparison(
            InMemoryNineXSnapshotSource(listOf(snapshot), source),
            source,
            plan,
            models,
            { log },
            checkpoints,
            CompareOptions(restart = true),
            { printed += it },
        )
        // The 9.x snapshot holds the team values in another order, and the derived level and channel.
        comparison(snapshot(6, SNAPSHOT_TAGS)).compare(order).passed.assert().isTrue()
        printed.none { it.startsWith("DIFFERS") }.assert().isTrue()
        comparison(snapshot(6, """{"region": ["west"], "team": ["a", "b", "c"], "level": ["1"], "channel": ["web"]}"""))
            .compare(order).passed.assert().isFalse()
        printed.single { it.startsWith("DIFFERS") }.assert().endsWith(
            "\$access.region: 9.x \"west\", V10 \"east\"; \$access.teams: 9.x [\"a\",\"b\",\"c\"], V10 [\"a\",\"b\"]",
        )
    }

    private companion object {
        /** The order's 9.x snapshot tags at version 6: as applied, the team values in another order, and derived. */
        const val SNAPSHOT_TAGS = """{"region": ["east"], "team": ["b", "a"], "level": ["1"], "channel": ["web"], "legacy": ["x"]}"""

        /** The order's V10 model, its tags as access attributes: two set by `OrderTagged`, two derived. */
        val taggedOrderModel = aggregate<TaggedOrderState>(NineXExample.CONTEXT, "order") {
            initial { TaggedOrderState(it.id) }
            evolve<OrderCreated>(revision = 2) { created ->
                address = created.address
                items = created.items
                totalAmount = created.items.map { it.totalPrice }.fold(BigDecimal.ZERO, BigDecimal::add)
                status = OrderStatus.CREATED
            }
            upgrade<OrderCreated>(1) { node -> (node as ObjectNode).apply { remove("orderId") } }
            evolve<AddressChanged> { address = it.shippingAddress }
            evolve<OrderPaid> { paid ->
                paidAmount += paid.amount
                if (paid.paid) {
                    status = OrderStatus.PAID
                }
            }
            evolve<OrderShipped> { status = OrderStatus.SHIPPED }
            evolve<OrderReceived> { status = OrderStatus.RECEIVED }
            evolve<OrderTagged> { tagged ->
                region = tagged.region
                teams = tagged.teams
            }
            retired(NineXExample.ORDER_ARCHIVED)
            access(AccessAttribute.single("region", String::class.java)) { it.region }
            access(AccessAttribute.multiple("teams", String::class.java)) { it.teams }
            access(AccessAttribute.single("level", Long::class.javaObjectType)) { it.items.size.toLong() }
            access(AccessAttribute.single("channel", String::class.java)) { "web" }
        }
    }
}

/** The example's order state with its tags as fields, as a V10 port declares them. */
internal class TaggedOrderState(val id: String) {
    var items: List<OrderItem> = emptyList()
    var address: ShippingAddress? = null
    var totalAmount: BigDecimal = BigDecimal.ZERO
    var paidAmount: BigDecimal = BigDecimal.ZERO
    var status: OrderStatus = OrderStatus.CREATED
    val payable: BigDecimal
        get() = totalAmount.minus(paidAmount)
    var region: String? = null
    var teams: Set<String> = emptySet()
}

/** The V10 event that sets the order's tags, which 9.x's tags event maps to. */
internal data class OrderTagged(val region: String?, val teams: Set<String>)
