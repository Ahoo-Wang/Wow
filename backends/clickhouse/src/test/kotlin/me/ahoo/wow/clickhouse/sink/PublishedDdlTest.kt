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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.sink.SinkFixtures.ORDER
import org.junit.jupiter.api.Test

/**
 * Holds the DDL that the page `operate/clickhouse` publishes to the statements the Sink runs, so that a column, an
 * engine or a key the Sink changes fails here until the fixtures, and the page that shows them (they are its
 * samples), say so:
 *
 * - `sink/shop_order.sql`, the statements of `ddl=auto` for the order aggregate of the `shop` context, in `default`;
 * - `sink/shop_order.cluster.sql`, the same tables written by hand for a cluster: a replicated local table behind a
 *   Distributed table of the name the Sink expects. Its columns, views and flat view are the Sink's; its engines,
 *   keys and Distributed tables are the page's advice, which only a cluster can try and this test does not.
 */
class PublishedDdlTest {
    private val schema = ClickHouseSinks(RecordingStatements(), SinkFixtures.MANIFEST).schemaOf(ORDER)
    private val flatView = schema.flatView(FlatLineage.EMPTY.then(schema.flatFingerprint))

    @Test
    fun `the DDL the page publishes should be the statements the Sink runs`() {
        val statements = listOf(ClickHouseCatalog.ddl(schema.database)) + schema.ddl + flatView
        sample("sink/shop_order.sql").assert().isEqualTo(statements.joinToString(";\n\n", postfix = ";\n"))
    }

    @Test
    fun `the cluster example should keep the Sink's columns, views and flat view`() {
        val statements = clusterStatements()
        statements.size.assert().isEqualTo(CLUSTER_STATEMENTS)
        val (stateEvents, state, events) = statements
        columnsOf(stateEvents).assert().isEqualTo(columnsOf(schema.ddl[0]))
        columnsOf(state).assert().isEqualTo(columnsOf(schema.ddl[1]))
        columnsOf(events).assert().isEqualTo(columnsOf(schema.ddl[2]))
        // Every table says the Sink's format, the Distributed ones the Sink reads it from among them.
        statements.subList(0, 6).forEach { it.assert().endsWith("\nCOMMENT 'wow-sink-format:1'") }
        statements.subList(3, 6).forEach {
            it.assert().contains(
                "ENGINE = Distributed('{cluster}', `default`, "
            ).endsWith("sipHash64(tenant_id, aggregate_id))\nCOMMENT 'wow-sink-format:1'")
        }
        statements[6].assert().isEqualTo(clustered(schema.ddl[3], schema.state, schema.stateEvents))
        statements[7].assert().isEqualTo(clustered(schema.ddl[4], schema.events, schema.stateEvents))
        statements[8].assert().isEqualTo(
            flatView.replaceFirst("`${schema.stateFlat}` AS", "`${schema.stateFlat}` ON CLUSTER '{cluster}' AS")
        )
    }

    /** The statement of a view on every node, reading and writing its shard's local tables. */
    private fun clustered(statement: String, vararg tables: String): String =
        tables.fold(statement) { each, table -> each.replace("`$table`", "`${table}_local`") }
            .replaceFirst(" TO ", " ON CLUSTER '{cluster}' TO ")

    private fun clusterStatements(): List<String> =
        sample("sink/shop_order.cluster.sql").lines().filterNot { it.startsWith("--") }.joinToString("\n")
            .split(";\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** The column definitions of a `CREATE TABLE`, between its parentheses. */
    private fun columnsOf(statement: String): List<String> =
        statement.lines().dropWhile { it != "(" }.drop(1).takeWhile { it != ")" }

    private fun sample(path: String): String = checkNotNull(javaClass.classLoader.getResource(path)).readText()

    private companion object {
        /** The cluster example's statements: three local tables, three Distributed tables, two views and the flat view. */
        const val CLUSTER_STATEMENTS = 9
    }
}
