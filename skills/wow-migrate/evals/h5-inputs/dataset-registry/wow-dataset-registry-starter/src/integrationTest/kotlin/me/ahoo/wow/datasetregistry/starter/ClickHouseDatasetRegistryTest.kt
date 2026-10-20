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

package me.ahoo.wow.datasetregistry.starter

import com.clickhouse.client.api.Client
import me.ahoo.test.asserts.assert
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetQueryBackendFactory
import me.ahoo.wow.clickhouse.query.ClickHouseDatasetOptions
import me.ahoo.wow.clickhouse.query.ClickHouseExecutor
import me.ahoo.wow.clickhouse.query.ClickHouseSource
import me.ahoo.wow.datasetregistry.api.DatasetRegistryErrorCodes
import me.ahoo.wow.datasetregistry.api.SkippedColumn
import me.ahoo.wow.datasetregistry.domain.DatasetRegistryException
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QueryDatasets
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import reactor.kotlin.test.test
import java.util.concurrent.TimeUnit

/** Drafting from a real ClickHouse table and proving the draft can be served. */
class ClickHouseDatasetRegistryTest {
    companion object {
        private const val DATABASE = "wow_registry"
        private lateinit var container: ClickHouseContainer
        private lateinit var client: Client

        @JvmStatic
        @BeforeAll
        fun start() {
            container = ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:24.8.14.39-alpine"))
            container.start()
            client = Client.Builder().addEndpoint(container.httpUrl).setUsername(container.username)
                .setPassword(container.password).useAsyncRequests(true).build()
            execute("CREATE DATABASE IF NOT EXISTS $DATABASE")
            execute(
                "CREATE TABLE $DATABASE.orders (id String, tenant_id String, city LowCardinality(String), " +
                    "amount Decimal(18, 2), created_at DateTime64(3, 'UTC'), depts Array(String), " +
                    "attrs Map(String, String)) ENGINE = ReplacingMergeTree ORDER BY id",
            )
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            client.close()
            container.stop()
        }

        private fun execute(sql: String) {
            client.query(sql).get(1, TimeUnit.MINUTES).close()
        }
    }

    private val executor by lazy { ClickHouseExecutor(client) }
    private val inspector by lazy { ClickHouseDatasetTableInspector(executor, DATABASE) }

    @Test
    fun `a draft declares the table's columns and can be served as it is`() {
        val draft = inspector.draft("orders", null).block()!!
        draft.skipped.assert().containsExactly(SkippedColumn("attrs", "Map(String, String)"))
        draft.document.at("/scope/tenant").stringValue().assert().isEqualTo("tenant_id")
        draft.document.at("/storage/deduplicate").stringValue().assert().isEqualTo("FINAL")
        val dataset = QueryDataset.parse(MaterializedNamedAggregate("analytics", "orders"), draft.document.toString())
        CompilingDatasetPublicationCheck(
            datasets = QueryDatasets(),
            registered = { emptySet() },
            aggregates = emptySet(),
            factory = ClickHouseDatasetQueryBackendFactory(executor),
        ).check(dataset).test().verifyComplete()
    }

    @Test
    fun `a draft from a named source binds its datasets to that source`() {
        val warehouse = ClickHouseSource(executor, DATABASE)
        val named = ClickHouseDatasetTableInspector(executor, "default", mapOf("warehouse" to warehouse))
        val draft = named.draft("orders", "warehouse").block()!!
        draft.document.at("/storage/source").stringValue().assert().isEqualTo("warehouse")
        draft.document.at("/storage/table").stringValue().assert().isEqualTo("$DATABASE.orders")
        val dataset = QueryDataset.parse(MaterializedNamedAggregate("analytics", "orders"), draft.document.toString())
        CompilingDatasetPublicationCheck(
            datasets = QueryDatasets(),
            registered = { emptySet() },
            aggregates = emptySet(),
            factory = ClickHouseDatasetQueryBackendFactory(
                executor,
                ClickHouseDatasetOptions(defaultDatabase = "default"),
                mapOf("warehouse" to warehouse),
            ),
        ).check(dataset).test().verifyComplete()
        named.draft("orders", "lake").test().expectErrorSatisfies {
            it.assert().isInstanceOf(DatasetRegistryException::class.java).hasMessageContaining("[lake]")
        }.verify()
    }

    @Test
    fun `a draft of a table that does not exist, or of a malformed name, is refused`() {
        listOf("$DATABASE.missing", "a.b.c", ".orders").forEach { table ->
            inspector.draft(table, null).test().expectErrorSatisfies {
                (it as DatasetRegistryException).errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_INVALID)
            }.verify()
        }
    }

    @Test
    fun `a declaration the table contradicts is not publishable`() {
        val draft = inspector.draft("$DATABASE.orders", null).block()!!.document
        (draft.get("fields") as tools.jackson.databind.node.ObjectNode).putObject("missing").putArray("types").add("STRING")
        val dataset = QueryDataset.parse(MaterializedNamedAggregate("analytics", "orders"), draft.toString())
        CompilingDatasetPublicationCheck(QueryDatasets(), { emptySet() }, emptySet(), ClickHouseDatasetQueryBackendFactory(executor))
            .check(dataset).test().expectErrorSatisfies {
                (it as DatasetRegistryException).errorCode.assert().isEqualTo(DatasetRegistryErrorCodes.DATASET_NOT_PUBLISHABLE)
                it.errorMsg.assert().contains("missing")
            }.verify()
    }
}
