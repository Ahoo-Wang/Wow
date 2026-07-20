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

package me.ahoo.wow.bi

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ClientException
import com.clickhouse.client.api.data_formats.ClickHouseBinaryFormatReader
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.client.api.query.QuerySettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.configuration.MetadataSearcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import reactor.test.scheduler.VirtualTimeScheduler
import tools.jackson.core.JacksonException
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("LargeClass")
class ClickHouseBiDeploymentInspectorTest {
    @Test
    fun `should configure and close the official client without opening a connection`() {
        val inspector = ClickHouseBiDeploymentInspector(
            ClickHouseClientOptions(
                endpoints = listOf(URI.create("http://clickhouse:8123")),
                username = "bi-user",
                password = "secret",
                connectionPoolEnabled = false,
                connectionTimeout = Duration.ofSeconds(1),
                connectionRequestTimeout = Duration.ofSeconds(2),
                socketTimeout = Duration.ofSeconds(4),
                executionTimeout = Duration.ZERO,
                maxConnections = 3,
                maxRetries = 1,
            ),
            inspectionTimeout = Duration.ofSeconds(4),
        )

        inspector.close()
    }

    @Test
    fun `should reject a socket timeout longer than the total inspection timeout`() {
        val failure = assertThrows<IllegalArgumentException> {
            ClickHouseBiDeploymentInspector(
                ClickHouseClientOptions(
                    endpoints = listOf(URI.create("http://clickhouse:8123")),
                    socketTimeout = Duration.ofSeconds(5),
                ),
                inspectionTimeout = Duration.ofSeconds(4),
            )
        }

        failure.message.assert().contains("socketTimeout", "inspectionTimeout")
    }

    @Test
    fun `should inspect an authoritative empty standalone catalog on the dedicated scheduler`() {
        val queryThread = AtomicReference<String>()
        val client = StubClickHouseCatalogClient { sql, parameters, columns ->
            queryThread.set(Thread.currentThread().name)
            if (sql.contains("registryTable")) {
                columns.assert().containsExactlyElementsOf(REGISTRY_TABLE_COLUMNS)
                return@StubClickHouseCatalogClient emptyList()
            }
            sql.assert().contains("FROM system.tables").doesNotContain("clusterAllReplicas")
            if (columns == CATALOG_COLUMNS) {
                sql.assert().contains("show_table_uuid_in_table_create_query_if_not_nil = 0")
            }
            parameters.assert().isEqualTo(
                mapOf(
                    "database" to OPTIONS.database,
                    "consumerDatabase" to OPTIONS.consumerDatabase,
                    "ownershipPrefix" to BI_OBJECT_METADATA_PREFIX,
                    "databaseTables" to "[]",
                    "consumerDatabaseTables" to "['__wow_bi_deployment']",
                )
            )
            columns.assert().containsExactlyElementsOf(OBJECT_KEY_COLUMNS)
            emptyList()
        }

        val inspection = ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).block()
            as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
        queryThread.get().assert().startsWith("wow-bi-catalog-inspection-")
    }

    @Test
    fun `should scope the standalone catalog query to desired and owned objects`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        val queryCount = AtomicInteger()
        val candidateName = "bi_aggregate_command_store"
        val client = StubClickHouseCatalogClient { sql, parameters, columns ->
            queryCount.incrementAndGet()
            when (columns) {
                REGISTRY_TABLE_COLUMNS -> {
                    parameters["registryDatabase"].assert().isEqualTo(OPTIONS.consumerDatabase)
                    parameters["registryTable"].toString().assert().startsWith("__wow_bi_registry_")
                    emptyList()
                }

                OBJECT_KEY_COLUMNS -> {
                    sql.assert()
                        .contains(
                            "startsWith(comment, {ownershipPrefix:String})",
                            "name IN {databaseTables:Array(String)}",
                            "name IN {consumerDatabaseTables:Array(String)}",
                        )
                        .doesNotContain("create_table_query")
                    parameters["ownershipPrefix"].assert().isEqualTo("wow-bi:")
                    parameters["databaseTables"].toString().assert().contains(
                        candidateName,
                        "bi_aggregate_state_store",
                        "bi_aggregate_state_last_store",
                    )
                    parameters["consumerDatabaseTables"].toString().assert().contains(
                        "bi_aggregate_command_queue",
                        "bi_aggregate_state_consumer",
                        "__wow_bi_deployment",
                    )
                    listOf(catalogRecord(database = OPTIONS.database, name = candidateName))
                }

                CATALOG_COLUMNS -> {
                    sql.assert()
                        .contains("create_table_query", "name IN {databaseTables:Array(String)}")
                        .doesNotContain("startsWith(comment", "ownershipPrefix")
                    parameters.containsKey("ownershipPrefix").assert().isFalse()
                    parameters["databaseTables"].toString().assert().contains(candidateName)
                    emptyList()
                }

                else -> error("Unexpected catalog columns: $columns")
            }
        }

        val inspection = ClickHouseBiDeploymentInspector(client)
            .inspect(OPTIONS, BiScriptOperation.Deploy, setOf(aggregate))
            .block() as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
        queryCount.get().assert().isEqualTo(3)
    }

    @Test
    fun `should prepare the scoped catalog on the caller scheduler`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        val planningThread = AtomicReference<String>()
        val recordingAggregate = object : NamedAggregate {
            override val contextName: String
                get() {
                    planningThread.compareAndSet(null, Thread.currentThread().name)
                    return aggregate.contextName
                }
            override val aggregateName: String
                get() {
                    planningThread.compareAndSet(null, Thread.currentThread().name)
                    return aggregate.aggregateName
                }
        }
        val requestScheduler = Schedulers.newSingle("request-event-loop")
        try {
            val inspection = Mono.defer {
                ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(emptyList()))
                    .inspect(OPTIONS, BiScriptOperation.Deploy, setOf(recordingAggregate))
            }.subscribeOn(requestScheduler).block() as BiDeploymentInspection.Available

            inspection.deployment.objects.assert().isEmpty()
            planningThread.get().assert()
                .startsWith("request-event-loop")
        } finally {
            requestScheduler.dispose()
        }
    }

    @Test
    fun `should reuse prepared desired object keys without replanning during inspection`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        var rejectMetadataAccess = false
        val guardedAggregate = object : NamedAggregate {
            override val contextName: String
                get() {
                    check(!rejectMetadataAccess) { "prepared inspection must not read aggregate metadata again" }
                    return aggregate.contextName
                }
            override val aggregateName: String
                get() {
                    check(!rejectMetadataAccess) { "prepared inspection must not read aggregate metadata again" }
                    return aggregate.aggregateName
                }
        }
        val preparation = BiScriptGenerator(OPTIONS).prepare(setOf(guardedAggregate))
        rejectMetadataAccess = true

        val inspection = ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(emptyList()))
            .inspect(OPTIONS, BiScriptOperation.Deploy, preparation)
            .block() as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
    }

    @Test
    @Suppress("LongMethod") // Keeps the prepared manifest, catalog fixture, and generated diagnostic in one proof.
    fun `should report repairable computed definition drift from prepared inspection`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        val generator = BiScriptGenerator(OPTIONS)
        val preparation = generator.prepare(setOf(aggregate))
        val desiredView = preparation.desiredObjects.first { desired -> desired.kind == BiObjectKind.VIEW }
        val viewMetadata = BiObjectMetadata(
            deploymentId = DESCRIPTOR.deploymentId,
            configurationFingerprint = DESCRIPTOR.configurationFingerprint,
            topologyFingerprint = DESCRIPTOR.topologyFingerprint,
            aggregate = desiredView.aggregate,
            kind = BiObjectKind.VIEW,
        )
        val anchorMetadata = BiObjectMetadata(
            deploymentId = DESCRIPTOR.deploymentId,
            configurationFingerprint = DESCRIPTOR.configurationFingerprint,
            topologyFingerprint = DESCRIPTOR.topologyFingerprint,
            kind = BiObjectKind.ANCHOR,
            consumerIdentity = BiConsumerIdentity.deterministic(DESCRIPTOR).value,
        )
        val catalog = records(
            catalogRecord(
                database = desiredView.key.database,
                name = desiredView.key.name,
                comment = BiObjectMetadataCodec.encode(viewMetadata),
                createTableQuery = "CREATE VIEW ${desiredView.key.database}.${desiredView.key.name} " +
                    "AS SELECT 1 AS drifted",
                asSelect = "SELECT 1 AS drifted",
            ),
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "__wow_bi_deployment",
                comment = BiObjectMetadataCodec.encode(anchorMetadata),
                createTableQuery = "CREATE VIEW ${OPTIONS.consumerDatabase}.__wow_bi_deployment " +
                    "AS SELECT 1 AS alive WHERE 0",
                asSelect = "SELECT 1 AS alive WHERE 0",
            ),
        )
        val client = StubClickHouseCatalogClient { sql, _, _ ->
            if (sql.contains("formatQuerySingleLineOrNull(JSONExtractString")) {
                listOf(
                    ClickHouseCatalogRecord(
                        mapOf(
                            "database" to desiredView.key.database,
                            "name" to desiredView.key.name,
                            "canonical_select" to checkNotNull(desiredView.expectedQuery).selectSql,
                        )
                    )
                )
            } else {
                catalog
            }
        }

        val inspection = ClickHouseBiDeploymentInspector(client)
            .inspect(OPTIONS, BiScriptOperation.Deploy, preparation)
            .block() as BiDeploymentInspection.Available
        val result = generator.generate(preparation, inspection = inspection)

        result.diagnostics.any { diagnostic ->
            diagnostic.message.contains("definition drift") && diagnostic.message.contains(desiredView.key.name)
        }.assert().isTrue()
        result.script.assert().contains(
            "CREATE OR REPLACE VIEW \"${desiredView.key.database}\".\"${desiredView.key.name}\""
        )
    }

    @Test
    fun `should scope every cluster replica catalog query to desired and owned objects`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        val candidateName = "bi_aggregate_command_store"
        val queryCount = AtomicInteger()
        val client = StubClickHouseCatalogClient { sql, parameters, columns ->
            queryCount.incrementAndGet()
            when {
                sql.contains("system.one") -> listOf(nodeRecord(NODE_A), nodeRecord(NODE_B))
                sql.contains("registryTable") -> emptyList()
                columns == OBJECT_KEY_COLUMNS -> {
                    sql.assert().contains(
                        "SELECT DISTINCT database, name",
                        "clusterAllReplicas",
                        "startsWith(comment, {ownershipPrefix:String})",
                        "name IN {databaseTables:Array(String)}",
                    ).doesNotContain("create_table_query")
                    parameters["cluster"].assert().isEqualTo(CLUSTER.name)
                    parameters["ownershipPrefix"].assert().isEqualTo("wow-bi:")
                    parameters["databaseTables"].toString().assert().contains(candidateName)
                    listOf(catalogRecord(database = CLUSTER_OPTIONS.database, name = candidateName))
                }

                else -> {
                    columns.assert().containsExactlyElementsOf(NODE_COLUMNS + CATALOG_COLUMNS)
                    sql.assert()
                        .contains("clusterAllReplicas", "create_table_query", "name IN {databaseTables:Array(String)}")
                        .doesNotContain("startsWith(comment", "ownershipPrefix")
                    parameters["cluster"].assert().isEqualTo(CLUSTER.name)
                    parameters.containsKey("ownershipPrefix").assert().isFalse()
                    parameters["databaseTables"].toString().assert().contains(candidateName)
                    emptyList()
                }
            }
        }

        val inspection = ClickHouseBiDeploymentInspector(client)
            .inspect(CLUSTER_OPTIONS, BiScriptOperation.Deploy, setOf(aggregate))
            .block() as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
        queryCount.get().assert().isEqualTo(5)
    }

    @Test
    fun `should bound concurrent catalog inspections`() {
        val activeQueries = AtomicInteger()
        val maximumActiveQueries = AtomicInteger()
        val firstFourQueries = CountDownLatch(4)
        val unexpectedFifthQuery = CountDownLatch(1)
        val releaseQueries = CountDownLatch(1)
        val client = StubClickHouseCatalogClient { _, _, _ ->
            val active = activeQueries.incrementAndGet()
            maximumActiveQueries.accumulateAndGet(active, ::maxOf)
            if (active > 4) {
                unexpectedFifthQuery.countDown()
            }
            firstFourQueries.countDown()
            try {
                releaseQueries.await(5, TimeUnit.SECONDS).assert().isTrue()
                emptyList()
            } finally {
                activeQueries.decrementAndGet()
            }
        }

        val futures = List(8) {
            ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).toFuture()
        }
        try {
            firstFourQueries.await(5, TimeUnit.SECONDS).assert().isTrue()
            unexpectedFifthQuery.await(250, TimeUnit.MILLISECONDS).assert().isFalse()
        } finally {
            releaseQueries.countDown()
        }
        CompletableFuture.allOf(*futures.toTypedArray()).get(5, TimeUnit.SECONDS)

        maximumActiveQueries.get().assert().isEqualTo(4)
    }

    @Test
    fun `should classify a saturated inspection scheduler as unavailable`() {
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val scheduler = Schedulers.newBoundedElastic(1, 1, "saturated-bi-inspection")
        val client = StubClickHouseCatalogClient { _, _, _ ->
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS).assert().isTrue()
            emptyList()
        }
        val inspector = ClickHouseBiDeploymentInspector(
            catalogClient = client,
            inspectionScheduler = scheduler,
        )
        val active = inspector.inspect(OPTIONS).toFuture()
        try {
            queryStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
            val queued = inspector.inspect(OPTIONS).toFuture()

            inspector.inspect(OPTIONS).test()
                .expectErrorMatches { error ->
                    error is BiDeploymentInspectionException.Unavailable &&
                        error.message == "ClickHouse BI catalog inspection is overloaded"
                }
                .verify()

            releaseQuery.countDown()
            CompletableFuture.allOf(active, queued).get(5, TimeUnit.SECONDS)
        } finally {
            releaseQuery.countDown()
            scheduler.dispose()
        }
    }

    @Test
    fun `should classify rejected scoped planning as unavailable`() {
        val aggregate = MetadataSearcher.localAggregates.single { it.aggregateName == "aggregate" }
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val scheduler = Schedulers.newBoundedElastic(1, 1, "saturated-bi-scope")
        val client = StubClickHouseCatalogClient { _, _, _ ->
            queryStarted.countDown()
            releaseQuery.await(5, TimeUnit.SECONDS).assert().isTrue()
            emptyList()
        }
        val inspector = ClickHouseBiDeploymentInspector(
            catalogClient = client,
            inspectionScheduler = scheduler,
        )
        val active = inspector.inspect(OPTIONS).toFuture()
        try {
            queryStarted.await(5, TimeUnit.SECONDS).assert().isTrue()
            val queued = inspector.inspect(OPTIONS).toFuture()

            inspector.inspect(OPTIONS, BiScriptOperation.Deploy, setOf(aggregate)).test()
                .expectErrorMatches { error ->
                    error is BiDeploymentInspectionException.Unavailable &&
                        error.message == "ClickHouse BI catalog inspection is overloaded"
                }
                .verify()

            releaseQuery.countDown()
            CompletableFuture.allOf(active, queued).get(5, TimeUnit.SECONDS)
        } finally {
            releaseQuery.countDown()
            scheduler.dispose()
        }
    }

    @Test
    fun `should decode owned objects and validate the Kafka consumer identity`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val metadata = BiObjectMetadata(
            deploymentId = DESCRIPTOR.deploymentId,
            configurationFingerprint = DESCRIPTOR.configurationFingerprint,
            topologyFingerprint = DESCRIPTOR.topologyFingerprint,
            aggregate = "example.order",
            kind = BiObjectKind.QUEUE,
            consumerIdentity = identity.value,
        )
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "example_order_command_queue",
                    engine = "Kafka",
                    engineFull = "Kafka('localhost:9093', 'wow.example.order.command', " +
                        "'wow-bi.${identity.value}.example_order_command_consumer', 'JSONAsString') " +
                        "SETTINGS kafka_num_consumers = 2",
                    comment = BiObjectMetadataCodec.encode(metadata),
                )
            )
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.single().metadata.assert().isEqualTo(metadata)
    }

    @Test
    fun `should validate every standalone store physical shape`() {
        val stores = listOf(
            catalogRecord(
                name = "example_order_command_store",
                engine = "ReplacingMergeTree",
                engineFull = "ReplacingMergeTree PARTITION BY toYYYYMM(create_time) ORDER BY id SETTINGS x = 1",
                comment = storeComment(),
                partitionKey = "toYYYYMM(create_time)",
                sortingKey = "id",
            ),
            catalogRecord(
                name = "example_order_state_store",
                engine = "ReplacingMergeTree",
                engineFull = "ReplacingMergeTree(version) PARTITION BY toYYYYMM(create_time) " +
                    "ORDER BY (tenant_id, aggregate_id, version)",
                comment = storeComment(),
                partitionKey = "toYYYYMM(create_time)",
                sortingKey = "tenant_id, aggregate_id, version",
            ),
            catalogRecord(
                name = "example_order_state_last_store",
                engine = "ReplacingMergeTree",
                engineFull = "ReplacingMergeTree(version) PARTITION BY toYYYYMM(first_event_time) " +
                    "ORDER BY (tenant_id, aggregate_id)",
                comment = storeComment(),
                partitionKey = "toYYYYMM(first_event_time)",
                sortingKey = "tenant_id, aggregate_id",
            ),
        )

        val available = ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(stores))
            .inspect(OPTIONS)
            .block() as BiDeploymentInspection.Available

        available.deployment.objects.assert().hasSize(3)
    }

    @Test
    fun `should reject a standalone store with a drifted column type`() {
        val store = catalogRecord(
            name = "example_order_command_store",
            engine = "ReplacingMergeTree",
            engineFull = "ReplacingMergeTree PARTITION BY toYYYYMM(create_time) ORDER BY id",
            comment = storeComment(),
            partitionKey = "toYYYYMM(create_time)",
            sortingKey = "id",
        )
        val client = StubClickHouseCatalogClient { sql, _, _ ->
            if (sql.contains("system.columns")) {
                expectedColumnRecords(listOf(store), "tenant_id" to "UInt64")
            } else {
                listOf(store)
            }
        }

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches { error ->
                error is BiDeploymentInspectionException.Inconsistent &&
                    error.message!!.contains("unexpected column schema") &&
                    error.message!!.contains("tenant_id")
            }
            .verify()
    }

    @Test
    fun `should reject standalone store engine and naming drift`() {
        val invalidEngine = catalogRecord(
            name = "example_order_state_store",
            engine = "ReplacingMergeTree",
            engineFull = "ReplacingMergeTree PARTITION BY toYYYYMM(create_time) " +
                "ORDER BY (tenant_id, aggregate_id, version)",
            comment = storeComment(),
            partitionKey = "toYYYYMM(create_time)",
            sortingKey = "tenant_id, aggregate_id, version",
        )
        ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(listOf(invalidEngine))).inspect(OPTIONS).test()
            .expectErrorMatches { error ->
                error is BiDeploymentInspectionException.Inconsistent &&
                    error.message!!.contains("unexpected engine definition")
            }
            .verify()

        val unsupportedName = invalidEngine.copyWithName("example_order_store")
        ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(listOf(unsupportedName))).inspect(OPTIONS).test()
            .expectErrorMatches { error ->
                error is BiDeploymentInspectionException.Inconsistent &&
                    error.message!!.contains("unsupported store name")
            }
            .verify()
    }

    @Test
    fun `should validate cluster local and distributed store physical shapes`() {
        val local = catalogRecord(
            name = "example_order_state_store_local",
            engine = "ReplicatedReplacingMergeTree",
            engineFull = "ReplicatedReplacingMergeTree(" +
                "'/clickhouse/test/test-cluster/tables/{shard}/bi_db/" +
                "example_order_state_store_local', '{replica}', version) " +
                "PARTITION BY toYYYYMM(create_time) ORDER BY (tenant_id, aggregate_id, version)",
            comment = storeComment(CLUSTER_OPTIONS),
            partitionKey = "toYYYYMM(create_time)",
            sortingKey = "tenant_id, aggregate_id, version",
        )
        val distributed = catalogRecord(
            name = "example_order_state_store",
            engine = "Distributed",
            engineFull = "Distributed('test-cluster', 'bi_db', 'example_order_state_store_local', " +
                "sipHash64(tenant_id, aggregate_id))",
            comment = storeComment(CLUSTER_OPTIONS),
        )
        val client = clusterClient(
            local.withNode(NODE_A),
            local.withNode(NODE_B),
            distributed.withNode(NODE_A),
            distributed.withNode(NODE_B),
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.assert().hasSize(2)
    }

    @Test
    fun `should reject mismatched replicated store path and replica`() {
        val distributed = catalogRecord(
            name = "example_order_state_store",
            engine = "Distributed",
            engineFull = "Distributed('test-cluster', 'bi_db', 'example_order_state_store_local', " +
                "sipHash64(tenant_id, aggregate_id))",
            comment = storeComment(CLUSTER_OPTIONS),
        )
        val invalidInvocations = listOf(
            "ReplicatedReplacingMergeTree('/clickhouse/other/test-cluster/tables/" +
                "{shard}/bi_db/example_order_state_store_local', '{replica}', version)",
            "ReplicatedReplacingMergeTree('/clickhouse/test/test-cluster/tables/" +
                "{shard}/bi_db/example_order_state_store_local', 'other-replica', version)",
        )

        invalidInvocations.forEach { invalidInvocation ->
            val invalidLocal = catalogRecord(
                name = "example_order_state_store_local",
                engine = "ReplicatedReplacingMergeTree",
                engineFull = "$invalidInvocation PARTITION BY toYYYYMM(create_time) " +
                    "ORDER BY (tenant_id, aggregate_id, version)",
                comment = storeComment(CLUSTER_OPTIONS),
                partitionKey = "toYYYYMM(create_time)",
                sortingKey = "tenant_id, aggregate_id, version",
            )
            val client = clusterClient(
                invalidLocal.withNode(NODE_A),
                invalidLocal.withNode(NODE_B),
                distributed.withNode(NODE_A),
                distributed.withNode(NODE_B),
            )

            ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).test()
                .expectErrorMatches { error ->
                    error is BiDeploymentInspectionException.Inconsistent &&
                        error.message!!.contains("unexpected replicated engine arguments")
                }
                .verify()
        }
    }

    @Test
    fun `should keep reset available for repairing drifted store keys`() {
        val drifted = catalogRecord(
            name = "example_order_command_store",
            engine = "ReplacingMergeTree",
            engineFull = "ReplacingMergeTree ORDER BY create_time",
            comment = storeComment(),
            sortingKey = "create_time",
        )
        val inspector = ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(listOf(drifted)))

        inspector.inspect(OPTIONS).test()
            .expectError(BiDeploymentInspectionException.Inconsistent::class.java)
            .verify()
        inspector.inspect(OPTIONS, BiScriptOperation.Reset(true)).test()
            .expectNextMatches { it is BiDeploymentInspection.Available }
            .verifyComplete()
    }

    @Test
    fun `should fail when cluster replicas disagree on an object definition`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A, engineFull = "View", comment = OWNED_COMMENT),
            catalogRecord(node = NODE_B, engineFull = "View from another replica", comment = OWNED_COMMENT),
        )

        ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("differs across replicas")
            }
            .verify()
    }

    @Test
    fun `should fail when an object is missing from a cluster replica`() {
        val client = clusterClient(catalogRecord(node = NODE_A, comment = OWNED_COMMENT))

        ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("is missing from a replica")
            }
            .verify()
    }

    @Test
    fun `should ignore replica-local foreign catalog differences`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A, name = "owned_view", comment = OWNED_COMMENT),
            catalogRecord(node = NODE_B, name = "owned_view", comment = OWNED_COMMENT),
            catalogRecord(node = NODE_A, name = "node_local_table", engine = "MergeTree", engineFull = "A"),
            catalogRecord(node = NODE_B, name = "node_local_table", engine = "MergeTree", engineFull = "B"),
            catalogRecord(
                node = NODE_A,
                database = CLUSTER_OPTIONS.consumerDatabase,
                name = "bi_aggregate_command_queue",
                engine = "Kafka",
            ),
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.map(ObservedBiObject::name).assert()
            .containsExactly("node_local_table", "owned_view", "bi_aggregate_command_queue")
    }

    @Test
    fun `should reject an empty or unknown cluster replica catalog`() {
        val emptyCluster = StubClickHouseCatalogClient(emptyList())
        ClickHouseBiDeploymentInspector(emptyCluster).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("returned no replicas")
            }
            .verify()

        val unknownReplica = clusterClient(catalogRecord(node = NODE_C, comment = OWNED_COMMENT))
        ClickHouseBiDeploymentInspector(unknownReplica).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("contains an unknown replica")
            }
            .verify()
    }

    @Test
    fun `should classify invalid cluster node ports and adapter arguments as inconsistent`() {
        val invalidPortClient = StubClickHouseCatalogClient(
            listOf(
                ClickHouseCatalogRecord(
                    mapOf(
                        "host_name" to "clickhouse",
                        "tcp_port" to "0",
                    )
                )
            )
        )
        ClickHouseBiDeploymentInspector(invalidPortClient).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("must be a valid port")
            }
            .verify()

        val failure = IllegalArgumentException("invalid catalog adapter argument")
        val invalidArgumentClient = StubClickHouseCatalogClient { _, _, _ -> throw failure }
        ClickHouseBiDeploymentInspector(invalidArgumentClient).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.cause === failure &&
                    it.message == failure.message
            }
            .verify()
    }

    @Test
    fun `should use a safe default message for catalog validation failures without a message`() {
        listOf(IllegalArgumentException(), IllegalStateException()).forEach { failure ->
            val client = StubClickHouseCatalogClient { _, _, _ -> throw failure }

            ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message == "ClickHouse BI catalog is inconsistent" &&
                        it.cause === failure
                }
                .verify()
        }
    }

    @Test
    fun `should accept a catalog that is identical on every cluster replica`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A),
            catalogRecord(node = NODE_B),
        )

        val available = ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).block()
            as BiDeploymentInspection.Available

        available.deployment.objects.assert().hasSize(1)
    }

    @Test
    fun `should reject duplicate owned rows from one cluster node when another replica is missing`() {
        val client = clusterClient(
            catalogRecord(node = NODE_A, comment = OWNED_COMMENT),
            catalogRecord(node = NODE_A, comment = OWNED_COMMENT),
        )

        ClickHouseBiDeploymentInspector(client).inspect(CLUSTER_OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("is missing from a replica")
            }
            .verify()
    }

    @Test
    fun `should reject incomplete catalog records`() {
        val client = StubClickHouseCatalogClient(
            records(
                ClickHouseCatalogRecord(
                    mapOf(
                        "database" to OPTIONS.database,
                        "name" to "",
                        "engine" to "View",
                        "engine_full" to "View",
                        "create_table_query" to "CREATE VIEW",
                        "comment" to "",
                    )
                )
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("[name] must not be blank")
            }
            .verify()
    }

    @Test
    fun `should reject duplicate owned definitions in a standalone catalog`() {
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(name = "owned_view", engineFull = "View A", comment = OWNED_COMMENT),
                catalogRecord(name = "owned_view", engineFull = "View B", comment = OWNED_COMMENT),
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("has duplicate definitions")
            }
            .verify()
    }

    @Test
    fun `should fail closed for every invalid owned Kafka queue identity`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val validComment = queueComment(identity.value)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val invalidQueues = listOf(
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "MergeTree",
                engineFull = "MergeTree",
                comment = validComment,
            ) to "must use the Kafka engine",
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "Kafka",
                engineFull = "Kafka('kafka:9092', 'topic', '$expectedGroup', 'JSONAsString')",
                comment = queueComment(null),
            ) to "is missing consumerIdentity",
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "Kafka",
                engineFull = "Kafka('kafka:9092', 'topic', 'wrong-group', 'JSONAsString')",
                comment = validComment,
            ) to "has an unexpected Kafka consumer group",
            catalogRecord(
                database = OPTIONS.consumerDatabase,
                name = "example_order_command_queue",
                engine = "Kafka",
                engineFull = "Kafka('$expectedGroup', 'topic', 'wrong-group', 'JSONAsString')",
                comment = validComment,
            ) to "has an unexpected Kafka consumer group",
        )

        invalidQueues.forEach { (record, expectedMessage) ->
            ClickHouseBiDeploymentInspector(StubClickHouseCatalogClient(records(record)))
                .inspect(OPTIONS)
                .test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains(expectedMessage)
                }
                .verify()
        }
    }

    @Test
    fun `should fail closed for malformed Kafka engine expressions`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val malformedDefinitions = listOf(
            "NotKafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString')",
            "Kafka",
            "Kafka()",
            "Kafka 'localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString'",
            "Kafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString'",
            "Kafka('localhost:9093'], 'wow.example.order.command', '$expectedGroup', 'JSONAsString')",
        )

        malformedDefinitions.forEach { engineFull ->
            val client = StubClickHouseCatalogClient(
                records(
                    catalogRecord(
                        database = OPTIONS.consumerDatabase,
                        name = "example_order_command_queue",
                        engine = "Kafka",
                        engineFull = engineFull,
                        comment = queueComment(identity.value),
                    )
                )
            )

            ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains("unexpected Kafka consumer group")
                }
                .verify()
        }
    }

    @Test
    fun `should parse whitespace and nested Kafka engine arguments without shifting consumer identity`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val whitespaceDefinition =
            "Kafka   ('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString')"
        val nestedDefinitions = listOf(
            "Kafka(concat('localhost', ':9093'), 'wow.example.order.command', " +
                "'$expectedGroup', 'JSONAsString')",
            "Kafka(['localhost:9093'], 'wow.example.order.command', '$expectedGroup', 'JSONAsString')",
            "Kafka('local\\'host:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString')",
            "Kafka('local''host:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString')",
        )

        ClickHouseBiDeploymentInspector(
            StubClickHouseCatalogClient(
                records(
                    catalogRecord(
                        database = OPTIONS.consumerDatabase,
                        name = "example_order_command_queue",
                        engine = "Kafka",
                        engineFull = whitespaceDefinition,
                        comment = queueComment(identity.value),
                    )
                )
            )
        ).inspect(OPTIONS).test()
            .expectNextCount(1)
            .verifyComplete()

        nestedDefinitions.forEach { nestedDefinition ->
            ClickHouseBiDeploymentInspector(
                StubClickHouseCatalogClient(
                    records(
                        catalogRecord(
                            database = OPTIONS.consumerDatabase,
                            name = "example_order_command_queue",
                            engine = "Kafka",
                            engineFull = nestedDefinition,
                            comment = queueComment(identity.value),
                        )
                    )
                )
            ).inspect(OPTIONS).test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains("unexpected Kafka bootstrap servers")
                }
                .verify()
        }
    }

    @Test
    fun `should accept a cluster Keeper state queue with the replica placeholder`() {
        val options = CLUSTER_OPTIONS.copy(kafkaOffsetStorage = KafkaOffsetStorage.KEEPER)
        val descriptor = BiDeploymentDescriptor.from(options)
        val identity = BiConsumerIdentity.deterministic(descriptor)
        val queueName = "example_order_state_queue"
        val expectedGroup = "wow-bi.${identity.value}.example_order_state_consumer"
        val keeperPath = "${options.kafkaKeeperPathPrefix}/${identity.value}/$queueName"
        val engineFull =
            "Kafka('localhost:9093', 'wow.example.order.state', '$expectedGroup', 'JSONAsString') " +
                "SETTINGS kafka_keeper_path = '$keeperPath', kafka_replica_name = '{replica}'"
        val client = clusterClient(
            catalogRecord(
                node = NODE_A,
                database = options.consumerDatabase,
                name = queueName,
                engine = "Kafka",
                engineFull = engineFull,
                comment = queueComment(identity.value, options),
            ),
            catalogRecord(
                node = NODE_B,
                database = options.consumerDatabase,
                name = queueName,
                engine = "Kafka",
                engineFull = engineFull,
                comment = queueComment(identity.value, options),
            ),
        )

        ClickHouseBiDeploymentInspector(client).inspect(options).test()
            .assertNext { inspection ->
                val available = inspection as BiDeploymentInspection.Available
                available.deployment.objects.single().name.assert().isEqualTo(queueName)
            }
            .verifyComplete()
    }

    @Test
    fun `should defer requested source validation while its deployment anchor is resetting`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val resettingAnchor = BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = DESCRIPTOR.deploymentId,
                configurationFingerprint = DESCRIPTOR.configurationFingerprint,
                topologyFingerprint = DESCRIPTOR.topologyFingerprint,
                phase = BiDeploymentPhase.RESETTING,
                aggregate = null,
                kind = BiObjectKind.ANCHOR,
                consumerIdentity = identity.value,
            )
        )
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "__wow_bi_deployment",
                    comment = resettingAnchor,
                ),
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "example_order_command_queue",
                    engine = "Kafka",
                    engineFull = "Kafka('old-kafka:9092', 'old.example.order.command', " +
                        "'$expectedGroup', 'JSONAsString')",
                    comment = queueComment(identity.value),
                ),
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `should fail closed when an owned Kafka queue source definition drifted`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val validComment = queueComment(identity.value)
        val invalidDefinitions = listOf(
            "Kafka('other:9092', 'wow.example.order.command', '$expectedGroup', 'JSONAsString')" to
                "unexpected Kafka bootstrap servers",
            "Kafka('localhost:9093', 'other.topic', '$expectedGroup', 'JSONAsString')" to
                "unexpected Kafka topic",
            "Kafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONEachRow')" to
                "unexpected Kafka format",
            "Kafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString') " +
                "SETTINGS kafka_keeper_path = '/unexpected', kafka_replica_name = '${identity.value}'" to
                "unexpected Keeper offset settings",
        )

        invalidDefinitions.forEach { (engineFull, expectedMessage) ->
            val client = StubClickHouseCatalogClient(
                records(
                    catalogRecord(
                        database = OPTIONS.consumerDatabase,
                        name = "example_order_command_queue",
                        engine = "Kafka",
                        engineFull = engineFull,
                        comment = validComment,
                    )
                )
            )

            ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains(expectedMessage)
                }
                .verify()
        }
    }

    @Test
    fun `should defer requested configuration changes to the generator for deploy and reset`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "example_order_command_queue",
                    engine = "Kafka",
                    engineFull = "Kafka('localhost:9093', 'wow.example.order.command', " +
                        "'$expectedGroup', 'JSONAsString')",
                    comment = queueComment(identity.value),
                )
            )
        )
        val changedOptions = OPTIONS.copy(
            kafkaBootstrapServers = "changed-kafka:9092",
            topicPrefix = "changed.",
            kafkaOffsetStorage = KafkaOffsetStorage.KEEPER,
            kafkaKeeperPathPrefix = "/changed",
        )
        val inspector = ClickHouseBiDeploymentInspector(client)

        inspector.inspect(changedOptions).test()
            .assertNext { inspection ->
                val available = inspection as BiDeploymentInspection.Available
                available.deployment.objects.single().metadata.assert().isNotNull()
            }
            .verifyComplete()

        inspector.inspect(changedOptions, BiScriptOperation.Reset(true)).test()
            .assertNext { inspection ->
                val available = inspection as BiDeploymentInspection.Available
                available.deployment.objects.single().metadata.assert().isNotNull()
            }
            .verifyComplete()
    }

    @Test
    fun `should not validate another deployment queue against the requested configuration`() {
        val foreignOptions = OPTIONS.copy(
            consumerGroupNamespace = "foreign",
            kafkaBootstrapServers = "foreign-kafka:9092",
            topicPrefix = "foreign.",
        )
        val foreignDescriptor = BiDeploymentDescriptor.from(foreignOptions)
        val foreignIdentity = BiConsumerIdentity.deterministic(foreignDescriptor)
        val foreignGroup = "wow-bi.${foreignIdentity.value}.example_order_command_consumer"
        val inspector = ClickHouseBiDeploymentInspector(
            StubClickHouseCatalogClient(
                records(
                    catalogRecord(
                        database = foreignOptions.consumerDatabase,
                        name = "example_order_command_queue",
                        engine = "Kafka",
                        engineFull = "Kafka('foreign-kafka:9092', 'foreign.example.order.command', " +
                            "'$foreignGroup', 'JSONAsString')",
                        comment = queueComment(foreignIdentity.value, foreignOptions),
                    )
                )
            )
        )

        inspector.inspect(OPTIONS).test()
            .assertNext { inspection ->
                val available = inspection as BiDeploymentInspection.Available
                available.deployment.objects.single().metadata!!.deploymentId.assert()
                    .isEqualTo(foreignDescriptor.deploymentId)
            }
            .verifyComplete()
    }

    @Test
    fun `should keep reset inspection strict for queue ownership identity and format`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val invalidDefinitions = listOf(
            "Kafka('localhost:9093', 'wow.example.order.command', 'wrong-group', 'JSONAsString')" to
                "unexpected Kafka consumer group",
            "Kafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONEachRow')" to
                "unexpected Kafka format",
        )

        invalidDefinitions.forEach { (engineFull, expectedMessage) ->
            val inspector = ClickHouseBiDeploymentInspector(
                StubClickHouseCatalogClient(
                    records(
                        catalogRecord(
                            database = OPTIONS.consumerDatabase,
                            name = "example_order_command_queue",
                            engine = "Kafka",
                            engineFull = engineFull,
                            comment = queueComment(identity.value),
                        )
                    )
                )
            )

            inspector.inspect(OPTIONS, BiScriptOperation.Reset(true)).test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains(expectedMessage)
                }
                .verify()
        }
    }

    @Test
    fun `should keep reset inspection strict for the owned queue name`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val queueName = "example_order_unknown_queue"
        val expectedGroup = "wow-bi.${identity.value}.example_order_unknown_consumer"
        val inspector = ClickHouseBiDeploymentInspector(
            StubClickHouseCatalogClient(
                records(
                    catalogRecord(
                        database = OPTIONS.consumerDatabase,
                        name = queueName,
                        engine = "Kafka",
                        engineFull = "Kafka('localhost:9093', 'wow.example.order.unknown', " +
                            "'$expectedGroup', 'JSONAsString')",
                        comment = queueComment(identity.value),
                    )
                )
            )
        )

        inspector.inspect(OPTIONS, BiScriptOperation.Reset(true)).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.message!!.contains("unsupported queue name")
            }
            .verify()
    }

    @Test
    fun `should fail closed when an owned Kafka queue Keeper identity drifted`() {
        val options = OPTIONS.copy(kafkaOffsetStorage = KafkaOffsetStorage.KEEPER)
        val descriptor = BiDeploymentDescriptor.from(options)
        val identity = BiConsumerIdentity.deterministic(descriptor)
        val expectedGroup = "wow-bi.${identity.value}.example_order_command_consumer"
        val expectedPath = "${options.kafkaKeeperPathPrefix}/${identity.value}/example_order_command_queue"
        val invalidDefinitions = listOf(
            "Kafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString') " +
                "SETTINGS kafka_keeper_path = '/unexpected', kafka_replica_name = '${identity.value}'" to
                "unexpected Kafka Keeper path",
            "Kafka('localhost:9093', 'wow.example.order.command', '$expectedGroup', 'JSONAsString') " +
                "SETTINGS kafka_keeper_path = '$expectedPath', kafka_replica_name = 'other'" to
                "unexpected Kafka Keeper replica name",
        )

        invalidDefinitions.forEach { (engineFull, expectedMessage) ->
            val client = StubClickHouseCatalogClient(
                records(
                    catalogRecord(
                        database = options.consumerDatabase,
                        name = "example_order_command_queue",
                        engine = "Kafka",
                        engineFull = engineFull,
                        comment = queueComment(identity.value, options),
                    )
                )
            )

            ClickHouseBiDeploymentInspector(client).inspect(options).test()
                .expectErrorMatches {
                    it is BiDeploymentInspectionException.Inconsistent &&
                        it.message!!.contains(expectedMessage)
                }
                .verify()
        }
    }

    @Test
    fun `should classify a malformed ownership marker as inconsistent`() {
        val client = StubClickHouseCatalogClient(
            records(
                catalogRecord(
                    database = OPTIONS.consumerDatabase,
                    name = "broken_anchor",
                    comment = "wow-bi:{",
                )
            )
        )

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Inconsistent &&
                    it.cause is JacksonException &&
                    it.errorInfo.errorCode == BiDeploymentInspectionException.INCONSISTENT_ERROR_CODE &&
                    it.message ==
                    "ClickHouse BI catalog object [${OPTIONS.consumerDatabase}.broken_anchor] " +
                    "contains invalid ownership metadata"
            }
            .verify()
    }

    @Test
    fun `should fail when inspection exceeds its total request timeout`() {
        val never = CountDownLatch(1)
        val client = StubClickHouseCatalogClient { _, _, _ ->
            never.await()
            emptyList()
        }

        ClickHouseBiDeploymentInspector(client, Duration.ofMillis(10))
            .inspect(OPTIONS)
            .test()
            .expectError(BiDeploymentInspectionException.Timeout::class.java)
            .verify()
    }

    @Test
    fun `should drain and close a native response that arrives after inspection timeout`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val responseFuture = CompletableFuture<QueryResponse>()
        val queryStarted = CountDownLatch(1)
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } answers {
            queryStarted.countDown()
            responseFuture
        }
        val timeoutScheduler = VirtualTimeScheduler.create()

        try {
            ClickHouseBiDeploymentInspector(
                NativeClickHouseCatalogClient(client),
                Duration.ofMillis(10),
                timeoutScheduler,
            ).inspect(OPTIONS).test()
                .then {
                    queryStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
                    timeoutScheduler.advanceTimeBy(Duration.ofMillis(10))
                }
                .expectError(BiDeploymentInspectionException.Timeout::class.java)
                .verify()

            responseFuture.complete(response).assert().isTrue()
            verify(exactly = 1, timeout = 1_000) { response.close() }
            responseFuture.isCancelled.assert().isFalse()
        } finally {
            timeoutScheduler.dispose()
        }
    }

    @Test
    fun `should isolate cancelled response cleanup from the blocking query scheduler`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val responseFuture = CompletableFuture<QueryResponse>()
        val queryStarted = CountDownLatch(1)
        val cleanupThread = AtomicReference<String>()
        val cancellation = ClickHouseQueryCancellation()
        val executor = Executors.newSingleThreadExecutor()
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } answers {
            queryStarted.countDown()
            responseFuture
        }
        every { response.close() } answers {
            cleanupThread.set(Thread.currentThread().name)
            throw IllegalStateException("close failed")
        }

        try {
            val queryFailure = CompletableFuture.supplyAsync(
                {
                    runCatching {
                        NativeClickHouseCatalogClient(client).query(
                            sql = "SELECT catalog",
                            parameters = emptyMap(),
                            columns = CATALOG_COLUMNS,
                            cancellation = cancellation,
                        )
                    }.exceptionOrNull()
                },
                executor,
            )
            queryStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            cancellation.cancel()
            (queryFailure.get(1, TimeUnit.SECONDS) is ClientException).assert().isTrue()
            responseFuture.complete(response).assert().isTrue()

            verify(exactly = 1, timeout = 1_000) { response.close() }
            cleanupThread.get().assert().startsWith("wow-bi-catalog-cleanup-")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `should isolate cancellation between concurrent native inspections`() {
        val client = mockk<Client>(relaxed = true)
        val responseA = mockk<QueryResponse>(relaxed = true)
        val responseB = mockk<QueryResponse>(relaxed = true)
        val readerB = mockk<ClickHouseBinaryFormatReader>(relaxed = true)
        val responseFutureA = CompletableFuture<QueryResponse>()
        val responseFutureB = CompletableFuture<QueryResponse>()
        val queryStartedA = CountDownLatch(1)
        val queryStartedB = CountDownLatch(1)
        val optionsA = OPTIONS.copy(database = "bi_a", consumerDatabase = "bi_a_consumer")
        val optionsB = OPTIONS.copy(database = "bi_b", consumerDatabase = "bi_b_consumer")
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } answers {
            when (secondArg<Map<String, Any>>()["database"]) {
                optionsA.database -> {
                    queryStartedA.countDown()
                    responseFutureA
                }

                optionsB.database -> {
                    queryStartedB.countDown()
                    responseFutureB
                }

                else -> error("Unexpected catalog query parameters: ${secondArg<Map<String, Any>>()}")
            }
        }
        every { client.newBinaryFormatReader(responseB) } returns readerB
        every { readerB.next() } returns null
        val inspector = ClickHouseBiDeploymentInspector(NativeClickHouseCatalogClient(client))
        val resultB = AtomicReference<BiDeploymentInspection?>()
        val errorB = AtomicReference<Throwable?>()
        val completedB = CountDownLatch(1)
        val subscriptionA = inspector.inspect(optionsA).subscribe()
        var subscriptionB: reactor.core.Disposable? = null

        try {
            queryStartedA.await(1, TimeUnit.SECONDS).assert().isTrue()
            subscriptionB = inspector.inspect(optionsB).subscribe(
                resultB::set,
                {
                    errorB.set(it)
                    completedB.countDown()
                },
                completedB::countDown,
            )
            queryStartedB.await(1, TimeUnit.SECONDS).assert().isTrue()

            subscriptionA.dispose()
            responseFutureB.complete(responseB).assert().isTrue()

            completedB.await(1, TimeUnit.SECONDS).assert().isTrue()
            errorB.get().assert().isNull()
            (resultB.get() is BiDeploymentInspection.Available).assert().isTrue()
            responseFutureA.complete(responseA).assert().isTrue()
            verify(exactly = 1, timeout = 1_000) { responseA.close() }
            verify(exactly = 2) { responseB.close() }
        } finally {
            subscriptionA.dispose()
            subscriptionB?.dispose()
            inspector.close()
        }
    }

    @Test
    fun `should not start a native query when cancellation already happened`() {
        val client = mockk<Client>(relaxed = true)
        val cancellation = ClickHouseQueryCancellation().apply { cancel() }

        try {
            val failure = assertThrows<ClientException> {
                NativeClickHouseCatalogClient(client).query(
                    sql = "SELECT catalog",
                    parameters = emptyMap(),
                    columns = CATALOG_COLUMNS,
                    cancellation = cancellation,
                )
            }

            failure.message.assert().contains("query was cancelled")
            verify(exactly = 0) {
                client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `should invoke cancellation callbacks exactly once before and after cancellation`() {
        val cancellation = ClickHouseQueryCancellation()
        val callbackCount = AtomicInteger()
        val registration = cancellation.invokeOnCancel(callbackCount::incrementAndGet)

        cancellation.cancel()
        cancellation.cancel()
        cancellation.invokeOnCancel(callbackCount::incrementAndGet).close()
        registration.close()

        callbackCount.get().assert().isEqualTo(2)
    }

    @Test
    fun `should close native response exactly once when cancellation wins before claim`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val cancellation = ClickHouseQueryCancellation()
        val responseFuture = object : CompletableFuture<QueryResponse>() {
            override fun get(): QueryResponse {
                cancellation.cancel()
                return super.get()
            }
        }.apply {
            complete(response)
        }
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns responseFuture
        val executor = Executors.newSingleThreadExecutor()

        try {
            val failure = CompletableFuture.supplyAsync(
                {
                    runCatching {
                        NativeClickHouseCatalogClient(client).query(
                            sql = "SELECT catalog",
                            parameters = emptyMap(),
                            columns = CATALOG_COLUMNS,
                            cancellation = cancellation,
                        )
                    }.exceptionOrNull()
                },
                executor,
            ).get(1, TimeUnit.SECONDS)

            (failure is ClientException).assert().isTrue()
            failure?.message.assert().contains("query was cancelled")
            verify(exactly = 1) { response.close() }
            verify(exactly = 0) { client.newBinaryFormatReader(response) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `should close native response exactly once when claim wins before cancellation`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val reader = mockk<ClickHouseBinaryFormatReader>(relaxed = true)
        val cancellation = ClickHouseQueryCancellation()
        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns CompletableFuture.completedFuture(response)
        every { client.newBinaryFormatReader(response) } returns reader
        every { response.close() } answers {
            releaseReader.countDown()
        }
        every { reader.next() } answers {
            readerStarted.countDown()
            while (true) {
                try {
                    releaseReader.await()
                    break
                } catch (_: InterruptedException) {
                    // Cancellation interrupts the query thread after the response was claimed.
                }
            }
            null
        }
        val executor = Executors.newSingleThreadExecutor()

        try {
            val result = CompletableFuture.supplyAsync(
                {
                    NativeClickHouseCatalogClient(client).query(
                        sql = "SELECT catalog",
                        parameters = emptyMap(),
                        columns = CATALOG_COLUMNS,
                        cancellation = cancellation,
                    )
                },
                executor,
            )
            readerStarted.await(1, TimeUnit.SECONDS).assert().isTrue()

            cancellation.cancel()
            releaseReader.await(1, TimeUnit.SECONDS).assert().isTrue()

            result.get(1, TimeUnit.SECONDS).assert().isEmpty()
            verify(exactly = 1) { reader.close() }
            verify(exactly = 1) { response.close() }
        } finally {
            releaseReader.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `should emit inspection timeout without waiting for graceful response close`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val reader = mockk<ClickHouseBinaryFormatReader>(relaxed = true)
        val responseFuture = CompletableFuture.completedFuture(response)
        val readerStarted = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns responseFuture
        every { client.newBinaryFormatReader(response) } returns reader
        every { reader.next() } answers {
            readerStarted.countDown()
            while (true) {
                try {
                    releaseReader.await()
                    break
                } catch (_: InterruptedException) {
                    // Simulate a socket read that ignores thread interruption.
                }
            }
            null
        }
        every { response.close() } answers {
            closeStarted.countDown()
            releaseClose.await(500, TimeUnit.MILLISECONDS)
        }
        val timeoutScheduler = VirtualTimeScheduler.create()

        try {
            ClickHouseBiDeploymentInspector(
                NativeClickHouseCatalogClient(client),
                Duration.ofMillis(10),
                timeoutScheduler,
            ).inspect(OPTIONS).test()
                .then {
                    readerStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
                    assertTimeoutPreemptively(Duration.ofMillis(200)) {
                        timeoutScheduler.advanceTimeBy(Duration.ofMillis(10))
                    }
                }
                .expectError(BiDeploymentInspectionException.Timeout::class.java)
                .verify(Duration.ofMillis(200))

            closeStarted.await(1, TimeUnit.SECONDS).assert().isTrue()
        } finally {
            releaseReader.countDown()
            releaseClose.countDown()
            timeoutScheduler.dispose()
        }
        verify(exactly = 1, timeout = 1_000) { response.close() }
    }

    @Test
    fun `should drain and close a native response that arrives after execution timeout`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val responseFuture = CompletableFuture<QueryResponse>()
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns responseFuture

        val failure = assertThrows<ClientException> {
            NativeClickHouseCatalogClient(client, Duration.ofMillis(10)).query(
                sql = "SELECT catalog",
                parameters = emptyMap(),
                columns = CATALOG_COLUMNS,
            )
        }

        failure.message.assert().contains("query timed out")
        (failure.cause is TimeoutException).assert().isTrue()
        responseFuture.complete(response).assert().isTrue()
        verify(exactly = 1, timeout = 1_000) { response.close() }
        responseFuture.isCancelled.assert().isFalse()
    }

    @Test
    fun `should classify a cancelled native query future as unavailable`() {
        val client = mockk<Client>(relaxed = true)
        val cancelledFuture = CompletableFuture<QueryResponse>().apply { cancel(false) }
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns cancelledFuture

        ClickHouseBiDeploymentInspector(NativeClickHouseCatalogClient(client)).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Unavailable &&
                    it.cause is ClientException &&
                    it.cause?.cause is CancellationException
            }
            .verify()

        verify(exactly = 0) { client.newBinaryFormatReader(any()) }
    }

    @Test
    fun `should classify exceptionally completed native query futures`() {
        val failures = listOf(
            IllegalStateException("transport failed") to BiDeploymentInspectionException.Unavailable::class.java,
            SocketTimeoutException("socket timed out") to BiDeploymentInspectionException.Timeout::class.java,
        )

        failures.forEach { (failure, expectedType) ->
            val client = mockk<Client>(relaxed = true)
            every {
                client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
            } returns CompletableFuture.failedFuture(failure)

            ClickHouseBiDeploymentInspector(NativeClickHouseCatalogClient(client)).inspect(OPTIONS).test()
                .expectErrorMatches { error ->
                    expectedType.isInstance(error) &&
                        error.cause is ClientException &&
                        error.cause?.cause === failure
                }
                .verify()

            verify(exactly = 0) { client.newBinaryFormatReader(any()) }
        }
    }

    @Test
    fun `should suppress late inspection failures after cancellation`() {
        val failures = listOf(
            BiDeploymentInspectionException.Unavailable("cancelled inspection"),
            IllegalArgumentException("cancelled argument"),
            IllegalStateException("cancelled state"),
        )

        failures.forEach { failure ->
            val client = object : ClickHouseCatalogClient {
                override fun query(
                    sql: String,
                    parameters: Map<String, Any>,
                    columns: List<String>,
                ): List<ClickHouseCatalogRecord> = error("The cancellation-aware overload must be used")

                override fun query(
                    sql: String,
                    parameters: Map<String, Any>,
                    columns: List<String>,
                    cancellation: ClickHouseQueryCancellation,
                ): List<ClickHouseCatalogRecord> {
                    cancellation.cancel()
                    throw failure
                }

                override fun close() = Unit
            }

            ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).block().assert()
                .isEqualTo(BiDeploymentInspection.Unavailable)
        }
    }

    @Test
    fun `should close native reader and response after reading catalog records`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val reader = mockk<ClickHouseBinaryFormatReader>(relaxed = true)
        val values = mapOf(
            "database" to OPTIONS.database,
            "name" to "foreign_view",
            "engine" to "View",
            "engine_full" to "View",
            "create_table_query" to "CREATE VIEW",
            "comment" to "",
        )
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns CompletableFuture.completedFuture(response)
        every { client.newBinaryFormatReader(response) } returns reader
        every { reader.next() } returns values andThen null
        every { reader.getString(any<String>()) } answers { values[firstArg()] }

        val records = NativeClickHouseCatalogClient(client).query(
            sql = "SELECT catalog",
            parameters = emptyMap(),
            columns = CATALOG_COLUMNS,
        )

        records.single().toObservedObject().name.assert().isEqualTo("foreign_view")
        verify(exactly = 1) { reader.close() }
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `should close native reader and response when reading a catalog record fails`() {
        val client = mockk<Client>(relaxed = true)
        val response = mockk<QueryResponse>(relaxed = true)
        val reader = mockk<ClickHouseBinaryFormatReader>(relaxed = true)
        val failure = IllegalStateException("catalog read failed")
        every {
            client.query(any<String>(), any<Map<String, Any>>(), any<QuerySettings>())
        } returns CompletableFuture.completedFuture(response)
        every { client.newBinaryFormatReader(response) } returns reader
        every { reader.next() } throws failure

        val actual = assertThrows<IllegalStateException> {
            NativeClickHouseCatalogClient(client).query(
                sql = "SELECT catalog",
                parameters = emptyMap(),
                columns = CATALOG_COLUMNS,
            )
        }

        (actual === failure).assert().isTrue()
        verify(exactly = 1) { reader.close() }
        verify(exactly = 1) { response.close() }
    }

    @Test
    fun `should classify native client failures as unavailable inspection`() {
        val failure = ClientException("upstream credentials were rejected")
        val client = StubClickHouseCatalogClient { _, _, _ -> throw failure }

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Unavailable &&
                    it.cause === failure &&
                    it.errorInfo.errorCode == BiDeploymentInspectionException.UNAVAILABLE_ERROR_CODE
            }
            .verify()
    }

    @Test
    fun `should classify native client operation timeout as inspection timeout`() {
        val failure = ClientException("query timed out", TimeoutException("operation timed out"))
        val client = StubClickHouseCatalogClient { _, _, _ -> throw failure }

        ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).test()
            .expectErrorMatches {
                it is BiDeploymentInspectionException.Timeout &&
                    it.cause === failure &&
                    it.errorInfo.errorCode == BiDeploymentInspectionException.TIMEOUT_ERROR_CODE
            }
            .verify()
    }

    @Test
    fun `should close its owned catalog client`() {
        val client = StubClickHouseCatalogClient(emptyList())

        ClickHouseBiDeploymentInspector(client).close()

        client.closed.assert().isTrue()
    }

    private fun clusterClient(vararg catalog: ClickHouseCatalogRecord): StubClickHouseCatalogClient {
        return StubClickHouseCatalogClient { sql, parameters, columns ->
            parameters["cluster"].assert().isEqualTo(CLUSTER.name)
            when {
                sql.contains("system.one") -> {
                    sql.assert().contains("show_table_uuid_in_table_create_query_if_not_nil = 0")
                    listOf(nodeRecord(NODE_A), nodeRecord(NODE_B))
                }

                sql.contains("registryTable") -> emptyList()
                sql.contains("system.columns") -> expectedColumnRecords(catalog.toList())
                else -> {
                    sql.assert().contains(
                        "system.tables",
                        "clusterAllReplicas",
                    )
                    if (columns == CATALOG_COLUMNS) {
                        sql.assert().contains("show_table_uuid_in_table_create_query_if_not_nil = 0")
                    }
                    catalog.toList()
                }
            }
        }
    }

    private fun records(vararg records: ClickHouseCatalogRecord): List<ClickHouseCatalogRecord> = records.toList()

    private fun nodeRecord(node: ClickHouseCatalogNode): ClickHouseCatalogRecord = ClickHouseCatalogRecord(
        mapOf(
            "host_name" to node.hostName,
            "tcp_port" to node.tcpPort.toString(),
        )
    )

    private fun catalogRecord(
        node: ClickHouseCatalogNode? = null,
        database: String = OPTIONS.database,
        name: String = "foreign_view",
        engine: String = "View",
        engineFull: String = "View",
        comment: String = "",
        createTableQuery: String = "CREATE VIEW",
        asSelect: String = "",
        partitionKey: String = "",
        sortingKey: String = "",
    ): ClickHouseCatalogRecord = ClickHouseCatalogRecord(
        buildMap {
            node?.let {
                put("host_name", it.hostName)
                put("tcp_port", it.tcpPort.toString())
            }
            put("database", database)
            put("name", name)
            put("engine", engine)
            put("engine_full", engineFull)
            put("create_table_query", createTableQuery)
            put("as_select", asSelect)
            put("comment", comment)
            put("partition_key", partitionKey)
            put("sorting_key", sortingKey)
        }
    )

    private fun ClickHouseCatalogRecord.withNode(node: ClickHouseCatalogNode): ClickHouseCatalogRecord {
        val catalogObject = toCatalogObject()
        return catalogRecord(
            node = node,
            database = catalogObject.observed.database,
            name = catalogObject.observed.name,
            engine = catalogObject.observed.engine,
            engineFull = catalogObject.observed.engineFull,
            comment = catalogObject.observed.metadata?.let(BiObjectMetadataCodec::encode).orEmpty(),
            partitionKey = catalogObject.partitionKey,
            sortingKey = catalogObject.sortingKey,
        )
    }

    private fun ClickHouseCatalogRecord.copyWithName(name: String): ClickHouseCatalogRecord {
        val catalogObject = toCatalogObject()
        return catalogRecord(
            database = catalogObject.observed.database,
            name = name,
            engine = catalogObject.observed.engine,
            engineFull = catalogObject.observed.engineFull,
            comment = catalogObject.observed.metadata?.let(BiObjectMetadataCodec::encode).orEmpty(),
            partitionKey = catalogObject.partitionKey,
            sortingKey = catalogObject.sortingKey,
        )
    }

    private fun queueComment(
        identity: String?,
        options: BiScriptOptions = OPTIONS,
    ): String {
        val descriptor = BiDeploymentDescriptor.from(options)
        return BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                aggregate = "example.order",
                kind = BiObjectKind.QUEUE,
                consumerIdentity = identity,
            )
        )
    }

    private fun storeComment(options: BiScriptOptions = OPTIONS): String {
        val descriptor = BiDeploymentDescriptor.from(options)
        return BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = descriptor.deploymentId,
                configurationFingerprint = descriptor.configurationFingerprint,
                topologyFingerprint = descriptor.topologyFingerprint,
                aggregate = "example.order",
                kind = BiObjectKind.STORE,
            )
        )
    }

    private inner class StubClickHouseCatalogClient(
        private val response: (
            sql: String,
            parameters: Map<String, Any>,
            columns: List<String>,
        ) -> List<ClickHouseCatalogRecord>,
    ) : ClickHouseCatalogClient {
        constructor(records: List<ClickHouseCatalogRecord>) : this({ sql, _, _ ->
            if (sql.contains("system.columns")) expectedColumnRecords(records) else records
        })

        var closed: Boolean = false
            private set

        override fun query(
            sql: String,
            parameters: Map<String, Any>,
            columns: List<String>,
        ): List<ClickHouseCatalogRecord> = response(sql, parameters, columns)

        override fun close() {
            closed = true
        }
    }

    private fun expectedColumnRecords(
        records: List<ClickHouseCatalogRecord>,
        override: Pair<String, String>? = null,
    ): List<ClickHouseCatalogRecord> = records.flatMap { record ->
        val catalogObject = record.toCatalogObject()
        if (catalogObject.observed.metadata?.kind != BiObjectKind.STORE) {
            return@flatMap emptyList()
        }
        val node = runCatching(record::toNode).getOrNull()
        val logicalName = catalogObject.observed.name.removeSuffix("_local")
        val columns = if (logicalName.endsWith("_command_store")) COMMAND_STORE_COLUMNS else STATE_STORE_COLUMNS
        columns.mapIndexed { index, (name, type) ->
            catalogColumnRecord(
                node = node,
                database = catalogObject.observed.database,
                table = catalogObject.observed.name,
                name = name,
                type = override?.takeIf { it.first == name }?.second ?: type,
                position = index + 1,
            )
        }
    }

    private fun catalogColumnRecord(
        node: ClickHouseCatalogNode?,
        database: String,
        table: String,
        name: String,
        type: String,
        position: Int,
    ): ClickHouseCatalogRecord = ClickHouseCatalogRecord(
        buildMap {
            node?.let {
                put("host_name", it.hostName)
                put("tcp_port", it.tcpPort.toString())
            }
            put("database", database)
            put("table", table)
            put("name", name)
            put("type", type)
            put("position", position.toString())
        }
    )

    private fun ClickHouseBiDeploymentInspector.inspect(
        options: BiScriptOptions,
        operation: BiScriptOperation = BiScriptOperation.Deploy,
        namedAggregates: Set<NamedAggregate> = emptySet(),
    ): Mono<BiDeploymentInspection> =
        inspect(options, operation, BiScriptGenerator(options).prepare(namedAggregates))

    private companion object {
        val OPTIONS = BiScriptOptions(consumerGroupNamespace = "test", topology = ClickHouseTopology.Standalone)
        val DESCRIPTOR = BiDeploymentDescriptor.from(OPTIONS)
        val CLUSTER = ClickHouseTopology.Cluster(name = "test-cluster", installation = "test")
        val CLUSTER_OPTIONS = OPTIONS.copy(topology = CLUSTER)
        val NODE_A = ClickHouseCatalogNode("clickhouse-a", 9000)
        val NODE_B = ClickHouseCatalogNode("clickhouse-b", 9000)
        val NODE_C = ClickHouseCatalogNode("clickhouse-c", 9000)
        val NODE_COLUMNS = listOf("host_name", "tcp_port")
        val OBJECT_KEY_COLUMNS = listOf("database", "name")
        val REGISTRY_TABLE_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "comment",
            "sorting_key",
        )
        val OWNED_COMMENT = BiObjectMetadataCodec.encode(
            BiObjectMetadata(
                deploymentId = DESCRIPTOR.deploymentId,
                configurationFingerprint = DESCRIPTOR.configurationFingerprint,
                topologyFingerprint = DESCRIPTOR.topologyFingerprint,
                aggregate = "example.order",
                kind = BiObjectKind.VIEW,
            )
        )
        val CATALOG_COLUMNS = listOf(
            "database",
            "name",
            "engine",
            "engine_full",
            "create_table_query",
            "as_select",
            "comment",
            "partition_key",
            "sorting_key",
        )
        val COMMAND_STORE_COLUMNS = listOf(
            "id" to "String",
            "context_name" to "String",
            "aggregate_name" to "String",
            "name" to "String",
            "header" to "Map(String, String)",
            "aggregate_id" to "String",
            "tenant_id" to "String",
            "owner_id" to "String",
            "space_id" to "String",
            "request_id" to "String",
            "aggregate_version" to "Nullable(UInt32)",
            "is_create" to "Bool",
            "is_void" to "Bool",
            "allow_create" to "Bool",
            "body_type" to "String",
            "body" to "String",
            "create_time" to "DateTime64(3, 'Asia/Shanghai')",
        )
        val STATE_STORE_COLUMNS = listOf(
            "id" to "String",
            "context_name" to "String",
            "aggregate_name" to "String",
            "header" to "Map(String, String)",
            "aggregate_id" to "String",
            "tenant_id" to "String",
            "owner_id" to "String",
            "space_id" to "String",
            "command_id" to "String",
            "request_id" to "String",
            "version" to "UInt32",
            "state" to "String",
            "body" to "Array(String)",
            "first_operator" to "String",
            "first_event_time" to "DateTime64(3, 'Asia/Shanghai')",
            "create_time" to "DateTime64(3, 'Asia/Shanghai')",
            "tags" to "Map(String, Array(String))",
            "deleted" to "Bool",
        )
    }
}
