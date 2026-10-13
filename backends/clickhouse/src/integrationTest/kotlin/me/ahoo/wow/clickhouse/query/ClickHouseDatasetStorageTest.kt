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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.QueryExecutionException
import me.ahoo.wow.query.QueryTimeoutException
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetBuilder
import me.ahoo.wow.query.list
import me.ahoo.wow.query.schema.QuerySchemaException
import me.ahoo.wow.query.schema.QueryValueKind
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** What only the ClickHouse backend does: deduplication, the declaration check, settings, timeouts and cancellation. */
class ClickHouseDatasetStorageTest {
    private val database = ClickHouseContainers.DATABASE
    private val factory = ClickHouseDatasetQueryBackendFactory(ClickHouseContainers.client)

    @Test
    fun `FINAL should read a replacing table's latest rows`(): Unit = runBlocking {
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
    fun `FINAL should need an engine that merges rows`(): Unit = runBlocking {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.plain")
        ClickHouseContainers.execute("CREATE TABLE $database.plain (id String, tenant_id String) ENGINE = MergeTree ORDER BY id")
        val error = assertThrows<QuerySchemaException> { schema(dataset("plain", "FINAL") { }) }
        error.message.assert().contains("FINAL")
    }

    @Test
    fun `a table that does not exist should leave the dataset unavailable`(): Unit = runBlocking {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.absent")
        val error = assertThrows<QuerySchemaException> { schema(dataset("absent", "NONE") { }) }
        error.message.assert().contains("absent")
    }

    @Test
    fun `the table should confirm the declaration`(): Unit = runBlocking {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.declared")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.declared (id String, tenant_id String, amount Decimal(18, 2), " +
                "note Nullable(String), tags Array(String), maybe Array(Nullable(String)), " +
                "created_at DateTime64(3, 'UTC')) ENGINE = MergeTree ORDER BY id",
        )
        val invalid = mapOf<String, QueryDatasetBuilder.() -> Unit>(
            "a missing column" to { field("missing") { types(QueryValueType.STRING) } },
            "a type of another family" to { field("amount") { types(QueryValueType.STRING) } },
            "a non-null declaration of a Nullable column" to {
                field("note") {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            },
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
            "a scalar declared as an array" to {
                field("amount") {
                    kind(QueryValueKind.ARRAY)
                    items { types(QueryValueType.DECIMAL) }
                }
            },
            "a date without the date semantic" to { field("created_at") { types(QueryValueType.STRING) } },
            "a nested field" to {
                field("amount") {
                    kind(QueryValueKind.OBJECT)
                    property("value") { types(QueryValueType.DECIMAL) }
                }
            },
        )
        invalid.forEach { (case, block) ->
            assertThrows<QuerySchemaException>(case) { schema(dataset("declared", "NONE", block)) }
        }
        schema(dataset("declared", "NONE") { field("amount") { types(QueryValueType.DECIMAL) } })
            .field(QueryField("amount")).assert().isNotNull()
        assertThrows<QuerySchemaException> {
            schema(
                QueryDataset.of(AggregateType("tck", "declared")) {
                    tenant("note")
                    field("note") { types(QueryValueType.STRING) }
                    storage(storage("declared", "NONE"))
                },
            )
        }.message.assert().contains("Nullable")
        assertThrows<QuerySchemaException> {
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
    fun `the backend's settings should fail a query that would exceed them`(): Unit = runBlocking {
        ClickHouseContainers.execute("DROP TABLE IF EXISTS $database.limited")
        ClickHouseContainers.execute(
            "CREATE TABLE $database.limited ENGINE = MergeTree ORDER BY id " +
                "AS SELECT toString(number) AS id, 't1' AS tenant_id FROM numbers(100000)",
        )
        val dataset = dataset("limited", "NONE") { }
        val limited = ClickHouseDatasetQueryBackendFactory(
            ClickHouseContainers.client,
            ClickHouseDatasetOptions(database = database, settings = mapOf("max_rows_to_read" to "10")),
        )
        val query = ListQuery(EqualFilter(QueryField("id"), "99999"))
        val schema = schema(dataset)
        DatasetTarget(dataset, factory).backend.list(QueryAdmission.Trusted.list(query, schema)).toList()
            .assert().hasSize(1)
        val error = assertThrows<QueryExecutionException> {
            DatasetTarget(dataset, limited).backend.list(QueryAdmission.Trusted.list(query, schema)).toList()
        }
        generateSequence<Throwable>(error) { it.cause }.any { it.message.orEmpty().contains("TOO_MANY_ROWS") }
            .assert().describedAs(error.toString()).isTrue()
    }

    @Test
    fun `a query should run under its timeout and fail as the core's timeout`(): Unit = runBlocking {
        endless()
        val target = DatasetTarget(endlessDataset(), factory)
        val query = QueryAdmission.Trusted.count(CountQuery(EqualFilter(QueryField("bucket"), 99)), target.schema())
            .withTimeout(Duration.ofSeconds(1))
        val error = assertThrows<QueryTimeoutException> { target.backend.count(query) }
        error.timeout.assert().isEqualTo(Duration.ofSeconds(1))
    }

    @Test
    fun `a stream that runs out of its time while sending rows should fail as the core's timeout`(): Unit = runBlocking {
        endless()
        val target = DatasetTarget(endlessDataset(), factory)
        val query = QueryAdmission.Trusted.list(ListQuery(EqualFilter(QueryField("bucket"), 1)), target.schema())
            .withTimeout(Duration.ofSeconds(1))
        assertThrows<QueryTimeoutException> { target.backend.stream(query).collect { } }
    }

    @Test
    fun `cancelling a query should stop it on the server`(): Unit = runBlocking {
        endless()
        val target = DatasetTarget(endlessDataset(), factory)
        val query = QueryAdmission.Trusted.list(ListQuery(EqualFilter(QueryField("bucket"), 99)), target.schema())
        val job = launch(Dispatchers.IO) { target.backend.stream(query).toList() }
        awaitRunning { it > 0 }.assert().describedAs("the query runs on the server").isGreaterThan(0L)
        job.cancel()
        awaitRunning { it == 0L }.assert().describedAs("the query is stopped").isEqualTo(0L)
    }

    private fun endless() {
        ClickHouseContainers.execute(
            "CREATE OR REPLACE VIEW $database.endless AS SELECT toString(number) AS id, 't1' AS tenant_id, " +
                "toInt64(number % 7) AS bucket FROM numbers(1000000000000)",
        )
    }

    private fun endlessDataset() = dataset("endless", "NONE") { field("bucket") { types(QueryValueType.INTEGER) } }

    private suspend fun awaitRunning(done: (Long) -> Boolean): Long {
        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        var count = running()
        while (!done(count) && System.nanoTime() < deadline) {
            delay(200)
            count = running()
        }
        return count
    }

    private suspend fun running(): Long = ClickHouseExecutor(ClickHouseContainers.client).all(
        ClickHouseStatement(
            "SELECT count() AS running FROM system.processes WHERE query LIKE '%endless%' " +
                "AND query NOT LIKE '%system.processes%'",
        ),
    ).single().get("running").longValue()

    private fun versions(deduplicate: String) = dataset("versions", deduplicate) {
        field("version") { types(QueryValueType.INTEGER) }
    }

    private suspend fun count(dataset: QueryDataset, filter: FilterExpression = MatchAllFilter): Long {
        val target = DatasetTarget(dataset, factory)
        return target.backend.count(QueryAdmission.Trusted.count(CountQuery(filter), target.schema()))
    }

    private suspend fun schema(dataset: QueryDataset) = DatasetTarget(dataset, factory).schema()

    private fun storage(table: String, deduplicate: String) = WowJson.mapper.createObjectNode()
        .put("table", "$database.$table")
        .put("deduplicate", deduplicate)

    private fun dataset(table: String, deduplicate: String, block: QueryDatasetBuilder.() -> Unit): QueryDataset =
        QueryDataset.of(AggregateType("tck", table)) {
            tenant("tenant_id")
            identity("id")
            field("id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            field("tenant_id") {
                types(QueryValueType.STRING)
                nullable(false)
            }
            storage(storage(table, deduplicate))
            block()
        }
}
