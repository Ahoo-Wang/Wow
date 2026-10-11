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

package me.ahoo.wow.clickhouse.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.list
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.query.target
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.kotlin.test.test
import java.time.Duration

/** What only the ClickHouse backend does: deduplication, the declaration check, limits and cancellation. */
class ClickHouseDatasetStorageTest {
    private val database = ClickHouseContainers.DATABASE

    @Test
    fun `FINAL reads a replacing table's latest rows`() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.versions")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.versions (id String, tenant_id String, version UInt64) " +
                "ENGINE = ReplacingMergeTree(version) ORDER BY id",
        )
        ClickHouseContainers.execute("SYSTEM STOP MERGES $database.versions")
        ClickHouseContainers.execute("INSERT INTO $database.versions VALUES ('a', 't1', 1), ('b', 't1', 1)")
        ClickHouseContainers.execute("INSERT INTO $database.versions VALUES ('a', 't1', 2)")
        count(versions("NONE")).assert().isEqualTo(3L)
        count(versions("FINAL")).assert().isEqualTo(2L)
    }

    @Test
    fun `FINAL needs an engine that merges rows`() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.plain")
        ClickHouseContainers.execute("CREATE TABLE $database.plain (id String, tenant_id String) ENGINE = MergeTree ORDER BY id")
        val dataset = dataset("plain", "FINAL") { }
        assertThrows<QuerySchemaValidationException> { schema(dataset) }.message.assert().contains("FINAL")
    }

    @Test
    fun `the table must confirm the declaration`() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.declared")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.declared (id String, tenant_id String, amount Decimal(18, 2), " +
                "note Nullable(String), tags Array(String), maybe Array(Nullable(String))) ENGINE = MergeTree ORDER BY id",
        )
        val invalid = mapOf<String, QueryDatasetBlock>(
            "a missing column" to { field("missing") { types(QueryValueType.STRING) } },
            "a type of another family" to { field("amount") { types(QueryValueType.STRING) } },
            "a non-null declaration of a Nullable column" to { field("note") { types(QueryValueType.STRING); nullable(false) } },
            "an array declared as a scalar" to { field("tags") { types(QueryValueType.STRING) } },
            "an array of another type" to {
                field("tags") {
                    kind(QueryValueKind.ARRAY)
                    items { types(QueryValueType.INTEGER) }
                }
            },
            "an unsupported column type" to {
                field("maybe") {
                    kind(QueryValueKind.ARRAY)
                    items { types(QueryValueType.STRING) }
                }
            },
        )
        invalid.forEach { (case, block) ->
            assertThrows<QuerySchemaValidationException>(case) { schema(dataset("declared", "NONE", block)) }
        }
        schema(dataset("declared", "NONE") { field("amount") { types(QueryValueType.DECIMAL) } })
            .field(me.ahoo.wow.api.query.QueryField("amount")).assert().isNotNull()
        assertThrows<QuerySchemaValidationException> {
            schema(
                QueryDataset.of(MaterializedNamedAggregate("tck", "declared")) {
                    tenant("note")
                    field("note") { types(QueryValueType.STRING) }
                    storage(storage("declared", "NONE"))
                },
            )
        }.message.assert().contains("Nullable")
        assertThrows<QuerySchemaValidationException> {
            schema(
                dataset("declared", "NONE") {
                    owner("tags")
                    field("tags") {
                        kind(QueryValueKind.ARRAY)
                        items { types(QueryValueType.STRING) }
                    }
                },
            )
        }.message.assert().contains("array column")
    }

    @Test
    fun `an entry's limits fail a query that would exceed them`() {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.limited")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.limited ENGINE = MergeTree ORDER BY id " +
                "AS SELECT toString(number) AS id, 't1' AS tenant_id FROM numbers(100000)",
        )
        val factory = ClickHouseDatasetQueryBackendFactory(
            ClickHouseExecutor(ClickHouseContainers.client),
            ClickHouseDatasetOptions(limits = mapOf(QueryEntry.HTTP to ClickHouseQueryLimits(maxRowsToRead = 10))),
        )
        val target = factory.target(dataset("limited", "NONE") { })
        val schema = target.schemaProvider.schema().block()!!
        val query = listQuery { filter { "id" eq "99999" } }
        target.backend.list(QueryAdmission.Trusted.list(query, schema, QueryEntry.IN_PROCESS)).test()
            .expectNextCount(1).verifyComplete()
        target.backend.list(QueryAdmission.Trusted.list(query, schema, QueryEntry.HTTP)).test()
            .expectErrorSatisfies { error ->
                generateSequence(error, Throwable::cause).any { it.message.orEmpty().contains("TOO_MANY_ROWS") }
                    .assert().describedAs(error.toString()).isTrue()
            }.verify()
    }

    @Test
    fun `cancelling a query stops it on the server`() {
        ClickHouseContainers.execute("DROP VIEW IF EXISTS $database.endless")
        ClickHouseContainers.execute(
            "CREATE VIEW $database.endless AS SELECT toString(number) AS id, 't1' AS tenant_id, " +
                "toInt64(number % 7) AS bucket FROM numbers(1000000000000)",
        )
        val target = ClickHouseDatasetQueryBackendFactory(ClickHouseExecutor(ClickHouseContainers.client))
            .target(dataset("endless", "NONE") { field("bucket") { types(QueryValueType.INTEGER) } })
        val schema = target.schemaProvider.schema().block()!!
        val query = listQuery { filter { "bucket" eq 99 } }
        val subscription = target.backend.list(QueryAdmission.Trusted.list(query, schema)).subscribe()
        awaitRunning { it > 0 }.assert().describedAs("the query runs on the server").isGreaterThan(0L)
        subscription.dispose()
        awaitRunning { it == 0L }.assert().describedAs("the query is stopped").isEqualTo(0L)
    }

    private fun awaitRunning(done: (Long) -> Boolean): Long {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        var count = running()
        while (!done(count) && System.nanoTime() < deadline) {
            Thread.sleep(200)
            count = running()
        }
        return count
    }

    private fun running(): Long = ClickHouseExecutor(ClickHouseContainers.client).rows(
        ClickHouseStatement(
            "SELECT count() AS running FROM system.processes WHERE query LIKE '%endless%' " +
                "AND query NOT LIKE '%system.processes%'",
            emptyMap(),
        ),
    ).single().block()!!.get("running").asLong()

    private fun versions(deduplicate: String) = dataset("versions", deduplicate) {
        field("version") { types(QueryValueType.INTEGER) }
    }

    private fun count(dataset: QueryDataset, filter: FilterExpression = MatchAllFilter): Long {
        val target = ClickHouseDatasetQueryBackendFactory(ClickHouseExecutor(ClickHouseContainers.client)).target(dataset)
        val schema = target.schemaProvider.schema().block()!!
        return target.backend.count(QueryAdmission.Trusted.count(filter, schema)).block()!!
    }

    private fun schema(dataset: QueryDataset) =
        ClickHouseDatasetQueryBackendFactory(ClickHouseExecutor(ClickHouseContainers.client))
            .target(dataset).schemaProvider.schema().block()!!

    private fun storage(table: String, deduplicate: String) = JsonSerializer.createObjectNode()
        .put("table", "$database.$table")
        .put("deduplicate", deduplicate)

    private fun dataset(table: String, deduplicate: String, block: QueryDatasetBlock): QueryDataset =
        QueryDataset.of(MaterializedNamedAggregate("tck", table)) {
            tenant("tenant_id")
            identity("id")
            field("id") { types(QueryValueType.STRING); nullable(false) }
            field("tenant_id") { types(QueryValueType.STRING); nullable(false) }
            storage(storage(table, deduplicate))
            block()
        }
}

private typealias QueryDatasetBlock = me.ahoo.wow.query.schema.QueryDatasetBuilder.() -> Unit
