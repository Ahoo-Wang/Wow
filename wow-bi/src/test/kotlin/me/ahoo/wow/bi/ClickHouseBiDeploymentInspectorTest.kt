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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertTimeoutPreemptively
import reactor.kotlin.test.test
import reactor.test.scheduler.VirtualTimeScheduler
import tools.jackson.core.JacksonException
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    fun `should inspect an authoritative empty standalone catalog off the caller thread`() {
        val queryThread = AtomicReference<String>()
        val client = StubClickHouseCatalogClient { sql, parameters, columns ->
            queryThread.set(Thread.currentThread().name)
            sql.assert()
                .contains(
                    "FROM system.tables",
                    "show_table_uuid_in_table_create_query_if_not_nil = 0",
                )
                .doesNotContain("clusterAllReplicas")
            parameters.assert().isEqualTo(
                mapOf(
                    "database" to OPTIONS.database,
                    "consumerDatabase" to OPTIONS.consumerDatabase,
                )
            )
            columns.assert().containsExactlyElementsOf(CATALOG_COLUMNS)
            emptyList()
        }

        val inspection = ClickHouseBiDeploymentInspector(client).inspect(OPTIONS).block()
            as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
        queryThread.get().assert().startsWith("boundedElastic-")
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
            verify(exactly = 1) { responseB.close() }
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
        val responses = ArrayDeque(
            listOf(
                listOf(nodeRecord(NODE_A), nodeRecord(NODE_B)),
                catalog.toList(),
            )
        )
        return StubClickHouseCatalogClient { sql, parameters, _ ->
            parameters["cluster"].assert().isEqualTo(CLUSTER.name)
            sql.assert().contains("show_table_uuid_in_table_create_query_if_not_nil = 0")
            if (responses.size == 2) {
                sql.assert().contains("system.one", "clusterAllReplicas")
            } else {
                sql.assert().contains("system.tables", "clusterAllReplicas")
            }
            responses.removeFirst()
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
            put("create_table_query", "CREATE VIEW")
            put("comment", comment)
        }
    )

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

    private class StubClickHouseCatalogClient(
        private val response: (
            sql: String,
            parameters: Map<String, Any>,
            columns: List<String>,
        ) -> List<ClickHouseCatalogRecord>,
    ) : ClickHouseCatalogClient {
        constructor(records: List<ClickHouseCatalogRecord>) : this({ _, _, _ -> records })

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

    private companion object {
        val OPTIONS = BiScriptOptions(consumerGroupNamespace = "test", topology = ClickHouseTopology.Standalone)
        val DESCRIPTOR = BiDeploymentDescriptor.from(OPTIONS)
        val CLUSTER = ClickHouseTopology.Cluster(name = "test-cluster", installation = "test")
        val CLUSTER_OPTIONS = OPTIONS.copy(topology = CLUSTER)
        val NODE_A = ClickHouseCatalogNode("clickhouse-a", 9000)
        val NODE_B = ClickHouseCatalogNode("clickhouse-b", 9000)
        val NODE_C = ClickHouseCatalogNode("clickhouse-c", 9000)
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
            "comment",
        )
    }
}
