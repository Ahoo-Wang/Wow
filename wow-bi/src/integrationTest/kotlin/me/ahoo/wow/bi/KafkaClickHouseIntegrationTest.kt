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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.expansion.BiTableNaming
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.container.ContainerImages
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.containers.Network
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

class KafkaClickHouseIntegrationTest {
    @Test
    @Suppress("LongMethod")
    fun `should consume Kafka messages and replay from earliest after reset`() {
        Network.newNetwork().use { network ->
            kafka(network).use { kafka ->
                clickHouse(network).use { clickHouse ->
                    kafka.start()
                    clickHouse.start()

                    val options = BiScriptOptions(
                        database = DATABASE,
                        consumerDatabase = CONSUMER_DATABASE,
                        topology = ClickHouseTopology.Standalone,
                        timezone = "UTC",
                        kafkaBootstrapServers = KAFKA_INTERNAL_BOOTSTRAP_SERVERS,
                        topicPrefix = TOPIC_PREFIX,
                        consumerGroupNamespace = "kafka-clickhouse-integration",
                    )
                    val aggregate = aggregateMetadata<ClickHouseExpansionAggregate, ClickHouseExpansionState>()
                    val naming = BiTableNaming(options)
                    val commandTopic = naming.toTopicName(aggregate, "command")
                    val stateTopic = naming.toTopicName(aggregate, "state")
                    val generator = BiScriptGenerator(options)
                    val firstDeploy = generator.generate(setOf(aggregate))

                    connection(clickHouse).use { connection ->
                        producer(kafka).use { producer ->
                            producer.sendJson(commandTopic, "command-1", commandMessage("command-1", 42))
                            producer.sendJson(stateTopic, "state-1", stateMessage(version = 1, scalar = 1))
                            producer.sendJson(stateTopic, "state-2", stateMessage(version = 2, scalar = 2))
                        }
                        firstDeploy.statements.forEach { statement -> connection.executeStatement(statement) }

                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(firstDeploy, "command_store")}", 1)
                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(firstDeploy, "state_store")}", 2)
                        connection.awaitLong(
                            "SELECT version FROM $DATABASE.${table(firstDeploy, "state_last")} " +
                                "WHERE aggregate_id = '$AGGREGATE_ID'",
                            2,
                        )
                        connection.queryCommand(firstDeploy).assert().isEqualTo(
                            CommandProjection(
                                isVoid = true,
                                body = "{\"value\":42}",
                                createTime = -1,
                            )
                        )
                        connection.awaitLong(
                            "SELECT nullable_scalar FROM $DATABASE.${table(firstDeploy, "state_last_root")} " +
                                "WHERE __aggregate_id = '$AGGREGATE_ID'",
                            2,
                        )

                        val redeploy = generator.generate(
                            setOf(aggregate),
                            BiScriptOperation.Deploy(firstDeploy.manifest),
                        )
                        redeploy.statements.forEach { statement -> connection.executeStatement(statement) }
                        connection.queryLong(
                            "SELECT count() FROM $DATABASE.${table(firstDeploy, "state_store")}"
                        ).assert().isEqualTo(2)

                        producer(kafka).use { producer ->
                            producer.sendJson(commandTopic, "command-2", commandMessage("command-2", 84))
                            producer.sendJson(stateTopic, "state-3", stateMessage(version = 3, scalar = 3))
                        }
                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(firstDeploy, "command_store")}", 2)
                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(firstDeploy, "state_store")}", 3)

                        val reset = generator.generate(
                            setOf(aggregate),
                            BiScriptOperation.Reset(
                                previousManifest = redeploy.manifest,
                                replayFromEarliestConfirmed = true,
                            ),
                        )
                        reset.script.assert().doesNotContain(consumerIdentity(firstDeploy.script))
                        reset.statements.forEach { statement -> connection.executeStatement(statement) }

                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(reset, "command_store")}", 2)
                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(reset, "state_store")}", 3)
                        connection.awaitLong(
                            "SELECT version FROM $DATABASE.${table(reset, "state_last")} " +
                                "WHERE aggregate_id = '$AGGREGATE_ID'",
                            3,
                        )

                        val deployAfterReset = generator.generate(
                            setOf(aggregate),
                            BiScriptOperation.Deploy(reset.manifest),
                        )
                        consumerIdentity(deployAfterReset.script).assert()
                            .isEqualTo(consumerIdentity(reset.script))
                        deployAfterReset.statements.forEach { statement -> connection.executeStatement(statement) }
                        producer(kafka).use { producer ->
                            producer.sendJson(stateTopic, "state-4", stateMessage(version = 4, scalar = 4))
                        }
                        connection.awaitLong("SELECT count() FROM $DATABASE.${table(reset, "state_store")}", 4)
                        connection.awaitLong(
                            "SELECT version FROM $DATABASE.${table(reset, "state_last")} " +
                                "WHERE aggregate_id = '$AGGREGATE_ID'",
                            4,
                        )
                    }
                }
            }
        }
    }

    private fun kafka(network: Network): ConfluentKafkaContainer = ConfluentKafkaContainer(
        DockerImageName.parse(ContainerImages.KAFKA)
    ).withNetwork(network)
        .withListener(KAFKA_INTERNAL_BOOTSTRAP_SERVERS)
        .withStartupTimeout(Duration.ofMinutes(2))

    private fun clickHouse(network: Network): ClickHouseContainer = ClickHouseContainer(
        DockerImageName.parse(CLICKHOUSE_IMAGE)
    ).withNetwork(network)

    private fun connection(container: ClickHouseContainer): Connection = DriverManager.getConnection(
        container.jdbcUrl,
        container.username,
        container.password,
    )

    private fun producer(container: ConfluentKafkaContainer): KafkaProducer<String, String> = KafkaProducer(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to container.bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to StringSerializer::class.java,
        )
    )

    private fun KafkaProducer<String, String>.sendJson(topic: String, key: String, value: Map<String, Any?>) {
        send(ProducerRecord(topic, key, JsonSerializer.writeValueAsString(value)))
            .get(10, TimeUnit.SECONDS)
    }

    private fun commandMessage(id: String, value: Int): Map<String, Any?> = mapOf(
        "id" to id,
        "contextName" to "bi-integration-service",
        "aggregateName" to "nullable",
        "name" to "TestCommand",
        "header" to emptyMap<String, String>(),
        "aggregateId" to AGGREGATE_ID,
        "tenantId" to "tenant",
        "ownerId" to "owner",
        "spaceId" to "space",
        "requestId" to "request-$id",
        "aggregateVersion" to null,
        "isCreate" to false,
        "isVoid" to true,
        "allowCreate" to false,
        "bodyType" to "TestCommand",
        "body" to mapOf("value" to value),
        "createTime" to -1,
    )

    private fun stateMessage(version: Int, scalar: Int): Map<String, Any?> = mapOf(
        "id" to "state-$version",
        "contextName" to "bi-integration-service",
        "aggregateName" to "nullable",
        "header" to emptyMap<String, String>(),
        "aggregateId" to AGGREGATE_ID,
        "tenantId" to "tenant",
        "ownerId" to "owner",
        "spaceId" to "space",
        "commandId" to "command-$version",
        "requestId" to "request-$version",
        "version" to version,
        "state" to mapOf("id" to AGGREGATE_ID, "nullableScalar" to scalar),
        "body" to emptyList<Any>(),
        "firstOperator" to "tester",
        "firstEventTime" to 1_000L * version,
        "createTime" to 1_000L * version,
        "tags" to emptyMap<String, List<String>>(),
        "deleted" to false,
    )

    private fun table(result: BiScriptResult, suffix: String): String =
        "${result.manifest.aggregates.single().tablePrefix}_$suffix"

    private fun consumerIdentity(script: String): String = requireNotNull(
        Regex("wow-bi\\.([0-9a-f]{32})\\.").find(script)?.groupValues?.get(1)
    )

    private fun Connection.executeStatement(sql: String) {
        createStatement().use { statement -> statement.execute(sql) }
    }

    private fun Connection.queryLong(sql: String): Long = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            check(result.next()) { "Query returned no rows: $sql" }
            result.getLong(1)
        }
    }

    private fun Connection.awaitLong(sql: String, expected: Long) {
        val deadline = Instant.now().plus(AWAIT_TIMEOUT)
        var actual = Long.MIN_VALUE
        while (Instant.now().isBefore(deadline)) {
            actual = runCatching { queryLong(sql) }.getOrDefault(Long.MIN_VALUE)
            if (actual == expected) {
                return
            }
            Thread.sleep(AWAIT_INTERVAL.toMillis())
        }
        actual.assert().isEqualTo(expected)
    }

    private fun Connection.queryCommand(result: BiScriptResult): CommandProjection =
        createStatement().use { statement ->
            statement.executeQuery(
                "SELECT is_void, body, toUnixTimestamp64Milli(create_time) " +
                    "FROM $DATABASE.${table(result, "command")} WHERE id = 'command-1'"
            ).use { rows ->
                check(rows.next()) { "Command row was not found" }
                CommandProjection(
                    isVoid = rows.getBoolean(1),
                    body = rows.getString(2),
                    createTime = rows.getLong(3),
                )
            }
        }

    private data class CommandProjection(
        val isVoid: Boolean,
        val body: String,
        val createTime: Long,
    )

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val DATABASE = "bi_kafka_it"
        const val CONSUMER_DATABASE = "bi_kafka_it_consumer"
        const val KAFKA_NETWORK_ALIAS = "kafka"
        const val KAFKA_INTERNAL_BOOTSTRAP_SERVERS = "$KAFKA_NETWORK_ALIAS:19092"
        const val TOPIC_PREFIX = "bi-e2e."
        const val AGGREGATE_ID = "aggregate-kafka"
        val AWAIT_TIMEOUT: Duration = Duration.ofSeconds(30)
        val AWAIT_INTERVAL: Duration = Duration.ofMillis(250)
    }
}
