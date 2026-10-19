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

package me.ahoo.wow.clickhouse.sink

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.clickhouse.sink.SinkFixtures.ORDER
import me.ahoo.wow.clickhouse.sink.SinkFixtures.recordOf
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The Sink across versions and names: writers of two state schemas during a rolling upgrade (finding X-15), the tables
 * checked again while it writes, and aggregates whose tables would be the same (finding X-16).
 */
class ClickHouseSinkVersionsTest {
    private val statements = RecordingStatements()
    private var now = 0L

    /** The order's state schema of the next version: one more scalar, so another flat view. */
    private val newer = Manifest(
        contexts = SinkFixtures.MANIFEST.contexts,
        defs = mapOf(
            "OrderState" to SinkFixtures.ORDER_STATE_SCHEMA + (
                "properties" to (SinkFixtures.ORDER_STATE_SCHEMA["properties"] as Map<*, *>) +
                    ("weight" to mapOf("type" to "number"))
                ),
        ),
    )

    private fun sinkOf(manifest: Manifest, ddl: DdlMode = DdlMode.AUTO): ClickHouseSink {
        val options = ClickHouseSinkOptions(ddl = ddl)
        return ClickHouseSink(
            ClickHouseSinks(statements, manifest, options).schemaOf(ORDER),
            statements,
            ddl,
            recheckEvery = 1.minutes,
            clock = { now },
        )
    }

    private fun lineage(): FlatLineage = FlatLineage.of(statements.comments["default.shop_order_state_flat"])

    @Test
    fun `an older writer restarting during a rolling upgrade should leave the newer writer's flat view`(): Unit =
        runTest {
            val old = sinkOf(SinkFixtures.MANIFEST)
            val new = sinkOf(newer)
            old.schema.flatFingerprint.assert().isNotEqualTo(new.schema.flatFingerprint)
            statements.create(old.schema)
            old.write(listOf(recordOf(version = 1)))
            lineage().fingerprints.assert().containsExactly(old.schema.flatFingerprint)

            // The new version starts: its view replaces the old one.
            statements.create(new.schema)
            new.write(listOf(recordOf(version = 2)))
            lineage().fingerprints.assert().containsExactly(old.schema.flatFingerprint, new.schema.flatFingerprint)

            // An old instance restarts: the view stays the new one, and the old instance writes on.
            val restarted = sinkOf(SinkFixtures.MANIFEST)
            restarted.write(listOf(recordOf(version = 3)))
            // Both check again later: neither replaces the view.
            now += 1.minutes.inWholeMilliseconds
            old.write(listOf(recordOf(version = 4)))
            new.write(listOf(recordOf(version = 5)))
            restarted.write(listOf(recordOf(version = 6)))

            statements.flatViews.size.assert().isEqualTo(2)
            lineage().current.assert().isEqualTo(new.schema.flatFingerprint)
            statements.inserts.size.assert().isEqualTo(6)
        }

    @Test
    fun `a writer of the same definition should not replace the flat view, nor one created without a lineage`():
        Unit = runTest {
        val first = sinkOf(SinkFixtures.MANIFEST)
        statements.create(first.schema)
        // A view an earlier Wow created, without a lineage: replaced once, to record it.
        statements.comments["default.shop_order_state_flat"] = ""
        first.write(listOf(recordOf(version = 1)))
        sinkOf(SinkFixtures.MANIFEST).write(listOf(recordOf(version = 2)))
        statements.flatViews.size.assert().isEqualTo(1)
    }

    @Test
    fun `validate should accept a newer writer's flat view by its existence, and check the tables again later`():
        Unit = runTest {
        sinkOf(SinkFixtures.MANIFEST).also { statements.create(it.schema) }.write(listOf(recordOf(version = 1)))
        val new = sinkOf(newer)
        statements.create(new.schema)
        new.write(listOf(recordOf(version = 1)))
        val old = sinkOf(SinkFixtures.MANIFEST, DdlMode.VALIDATE)
        old.write(listOf(recordOf(version = 2)))
        statements.flatViews.size.assert().isEqualTo(2)

        // A table dropped meanwhile: seen once the interval has passed, and the writes fail until it is back.
        statements.tables.remove("default.shop_order_events")
        old.write(listOf(recordOf(version = 3)))
        now += 1.minutes.inWholeMilliseconds
        val error = runCatching { old.write(listOf(recordOf(version = 4))) }.exceptionOrNull()
        error!!.message.assert().contains("shop_order_events does not exist")
        statements.create(new.schema)
        old.write(listOf(recordOf(version = 4)))
        statements.inserts.size.assert().isEqualTo(5)
    }

    @Test
    fun `validate should refuse a flat view of another definition that no newer writer made`(): Unit = runTest {
        val new = sinkOf(newer, DdlMode.VALIDATE)
        val old = sinkOf(SinkFixtures.MANIFEST)
        statements.create(old.schema)
        statements.comments["default.shop_order_state_flat"] = FlatLineage(listOf(old.schema.flatFingerprint)).comment
        val error = runCatching { new.write(listOf(recordOf())) }.exceptionOrNull()
        error!!.message.assert().contains("shop_order_state_flat has the columns")
        statements.executed.assert().isEmpty()
    }

    @Test
    fun `none should never check the tables`(): Unit = runTest {
        val sink = sinkOf(SinkFixtures.MANIFEST, DdlMode.NONE)
        sink.write(listOf(recordOf()))
        now += 1.minutes.inWholeMilliseconds
        sink.write(listOf(recordOf(version = 2)))
        statements.executed.assert().isEmpty()
        statements.inserts.size.assert().isEqualTo(2)
        assertThrows<IllegalArgumentException> {
            ClickHouseSink(sink.schema, statements, DdlMode.AUTO, recheckEvery = 0.milliseconds)
        }
    }

    @Test
    fun `a lineage should keep the last definitions, and know an older one`() {
        FlatLineage.of(null).assert().isEqualTo(FlatLineage.EMPTY)
        FlatLineage.of("").assert().isEqualTo(FlatLineage.EMPTY)
        FlatLineage.of("an operator's note").current.assert().isNull()
        val lineage = FlatLineage.of("wow-sink-flat:a, b,,c")
        lineage.fingerprints.assert().containsExactly("a", "b", "c")
        lineage.supersedes("a").assert().isTrue()
        lineage.supersedes("c").assert().isFalse()
        lineage.supersedes("d").assert().isFalse()
        lineage.then("a").fingerprints.assert().containsExactly("b", "c", "a")
        lineage.then("a").hashCode().assert().isEqualTo(FlatLineage(listOf("b", "c", "a")).hashCode())
        lineage.toString().assert().isEqualTo("FlatLineage([a, b, c])")
        val long = (1..FlatLineage.MAX + 2).fold(FlatLineage.EMPTY) { kept, n -> kept.then("f$n") }
        long.fingerprints.size.assert().isEqualTo(FlatLineage.MAX)
        long.fingerprints.first().assert().isEqualTo("f3")
        FlatLineage.of(long.comment).assert().isEqualTo(long)
    }

    private fun manifestOf(vararg types: AggregateType) = Manifest(
        contexts = types.groupBy { it.context }.map { (context, aggregates) ->
            ContextManifest(
                context,
                aggregates = aggregates.map {
                    AggregateManifest(
                        it.name,
                        state = StateManifest("#/\$defs/OrderState")
                    )
                },
            )
        },
        defs = SinkFixtures.MANIFEST.defs,
    )

    @Test
    fun `aggregates whose tables would be the same should fail the sinks with both named`() {
        val dashed = runCatching {
            ClickHouseSinks(
                statements,
                manifestOf(AggregateType("shop", "order-item"), AggregateType("shop", "Order_Item")),
            )
        }.exceptionOrNull()
        dashed.assert().isInstanceOf(IllegalStateException::class.java)
        dashed!!.message.assert()
            .contains("shop_order_item_state of shop.order-item and shop.Order_Item")
            .contains("rename one of the aggregates")
        // One aggregate's table is another's.
        val nested = runCatching {
            ClickHouseSinks(
                statements,
                manifestOf(AggregateType("shop", "order"), AggregateType("shop", "order_state"))
            )
        }.exceptionOrNull()
        nested!!.message.assert().contains("shop_order_state_events of shop.order and shop.order_state")
        // Context and aggregate split otherwise.
        ClickHouseSinkSchema.collisionsOf(listOf(AggregateType("a_b", "c"), AggregateType("a", "b_c"))).keys
            .assert().contains("a_b_c_state")
    }

    @Test
    fun `aggregates of distinct tables should keep their names`() {
        val sinks = ClickHouseSinks(
            statements,
            manifestOf(ORDER, AggregateType("shop", "cart"), AggregateType("Billing-EU", "invoice")),
        )
        sinks.schemaOf(
            AggregateType("Billing-EU", "invoice")
        ).target.assert().isEqualTo("billing_eu_invoice_state_events")
        sinks.schemaOf(ORDER).target.assert().isEqualTo("shop_order_state_events")
        ClickHouseSinkSchema.collisionsOf(listOf(ORDER, ORDER)).assert().isEmpty()
        ClickHouseSinkSchema.namesOf(ORDER).assert().containsExactly(
            "shop_order_state_events",
            "shop_order_state",
            "shop_order_events",
            "shop_order_state_flat",
            "shop_order_state_mv",
            "shop_order_events_mv",
        )
    }
}
