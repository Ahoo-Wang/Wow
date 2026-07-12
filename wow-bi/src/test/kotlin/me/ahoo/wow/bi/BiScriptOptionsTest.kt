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
import org.junit.jupiter.api.Test
import kotlin.reflect.full.declaredMemberProperties

class BiScriptOptionsTest {
    @Test
    fun `should model standalone without cluster state`() {
        ClickHouseTopology.Standalone.assert().isEqualTo(ClickHouseTopology.Standalone)
        ClickHouseTopology.Standalone::class.declaredMemberProperties.assert().isEmpty()
    }

    @Test
    fun `should use designed cluster defaults`() {
        ClickHouseTopology.Cluster().assert().isEqualTo(
            ClickHouseTopology.Cluster(
                name = "{cluster}",
                installation = "{installation}",
                shard = "{shard}",
                replica = "{replica}",
            )
        )
    }

    @Test
    fun `should reject invalid cluster values`() {
        listOf<() -> ClickHouseTopology.Cluster>(
            { ClickHouseTopology.Cluster(name = " ") },
            { ClickHouseTopology.Cluster(installation = "bad\nvalue") },
            { ClickHouseTopology.Cluster(shard = "\t") },
            { ClickHouseTopology.Cluster(replica = "bad\u0000value") },
        ).all { runCatching(it).isFailure }.assert().isTrue()
    }

    @Test
    fun `should accept cluster values at the maximum length`() {
        val value = "x".repeat(ClickHouseTopology.Cluster.MAX_VALUE_LENGTH)

        ClickHouseTopology.Cluster(
            name = value,
            installation = value,
            shard = value,
            replica = value,
        ).assert().isEqualTo(
            ClickHouseTopology.Cluster(
                name = value,
                installation = value,
                shard = value,
                replica = value,
            )
        )
    }

    @Test
    fun `should reject every cluster value above the maximum length`() {
        val maximum = ClickHouseTopology.Cluster.MAX_VALUE_LENGTH
        val tooLong = "x".repeat(maximum + 1)
        val invalidValues = listOf(
            "name" to { ClickHouseTopology.Cluster(name = tooLong) },
            "installation" to { ClickHouseTopology.Cluster(installation = tooLong) },
            "shard" to { ClickHouseTopology.Cluster(shard = tooLong) },
            "replica" to { ClickHouseTopology.Cluster(replica = tooLong) },
        )

        invalidValues.forEach { (field, createCluster) ->
            val failure = runCatching(createCluster).exceptionOrNull()

            failure.assert().isNotNull()
            failure!!.message.assert().contains(field, (maximum + 1).toString(), maximum.toString())
        }
    }

    @Test
    fun `should use the designed defaults`() {
        val options = BiScriptOptions()

        options.database.assert().isEqualTo("bi_db")
        options.consumerDatabase.assert().isEqualTo("bi_db_consumer")
        options.topology.assert().isEqualTo(ClickHouseTopology.Cluster())
        options.timezone.assert().isEqualTo("Asia/Shanghai")
        options.kafkaBootstrapServers.assert().isEqualTo("localhost:9093")
        options.topicPrefix.assert().isEqualTo("wow.")
        options.maxExpansionDepth.assert().isEqualTo(5)
        options.unsupportedTypeStrategy.assert().isEqualTo(UnsupportedTypeStrategy.RAW_JSON)
        BiScriptOptions::class.declaredMemberProperties.map { it.name }.assert()
            .doesNotContain("cluster", "installation", "shard", "replica")
    }

    @Test
    fun `should reject blank required values`() {
        listOf<() -> BiScriptOptions>(
            { BiScriptOptions(database = " ") },
            { BiScriptOptions(consumerDatabase = " ") },
            { BiScriptOptions(timezone = " ") },
            { BiScriptOptions(kafkaBootstrapServers = " ") },
            { BiScriptOptions(topicPrefix = " ") },
        ).forEach { createOptions ->
            runCatching(createOptions).isFailure.assert().isTrue()
        }
    }

    @Test
    fun `should reject control characters in required values`() {
        listOf<() -> BiScriptOptions>(
            { BiScriptOptions(database = "bi\u0000db") },
            { BiScriptOptions(consumerDatabase = "bi\ndb") },
            { BiScriptOptions(timezone = "Asia\nShanghai") },
            { BiScriptOptions(kafkaBootstrapServers = "localhost\n9093") },
            { BiScriptOptions(topicPrefix = "wow.\u0000") },
        ).forEach { createOptions ->
            runCatching(createOptions).isFailure.assert().isTrue()
        }
    }

    @Test
    fun `should accept every required value at its maximum length`() {
        listOf(
            { BiScriptOptions(database = "x".repeat(BiScriptOptions.MAX_DATABASE_LENGTH)) },
            {
                BiScriptOptions(
                    consumerDatabase = "x".repeat(BiScriptOptions.MAX_CONSUMER_DATABASE_LENGTH)
                )
            },
            { BiScriptOptions(timezone = "x".repeat(BiScriptOptions.MAX_TIMEZONE_LENGTH)) },
            {
                BiScriptOptions(
                    kafkaBootstrapServers = "x".repeat(BiScriptOptions.MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH)
                )
            },
            { BiScriptOptions(topicPrefix = "x".repeat(BiScriptOptions.MAX_TOPIC_PREFIX_LENGTH)) },
        ).forEach { createOptions ->
            runCatching(createOptions).isSuccess.assert().isTrue()
        }
    }

    @Test
    fun `should reject every required value above its maximum length`() {
        val invalidValues = listOf(
            Triple(
                "database",
                BiScriptOptions.MAX_DATABASE_LENGTH,
                {
                    BiScriptOptions(
                        database = "x".repeat(BiScriptOptions.MAX_DATABASE_LENGTH + 1)
                    )
                },
            ),
            Triple(
                "consumerDatabase",
                BiScriptOptions.MAX_CONSUMER_DATABASE_LENGTH,
                {
                    BiScriptOptions(
                        consumerDatabase = "x".repeat(BiScriptOptions.MAX_CONSUMER_DATABASE_LENGTH + 1)
                    )
                },
            ),
            Triple(
                "timezone",
                BiScriptOptions.MAX_TIMEZONE_LENGTH,
                {
                    BiScriptOptions(
                        timezone = "x".repeat(BiScriptOptions.MAX_TIMEZONE_LENGTH + 1)
                    )
                },
            ),
            Triple(
                "kafkaBootstrapServers",
                BiScriptOptions.MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH,
                {
                    BiScriptOptions(
                        kafkaBootstrapServers = "x".repeat(
                            BiScriptOptions.MAX_KAFKA_BOOTSTRAP_SERVERS_LENGTH + 1
                        )
                    )
                },
            ),
            Triple(
                "topicPrefix",
                BiScriptOptions.MAX_TOPIC_PREFIX_LENGTH,
                {
                    BiScriptOptions(
                        topicPrefix = "x".repeat(BiScriptOptions.MAX_TOPIC_PREFIX_LENGTH + 1)
                    )
                },
            ),
        )

        invalidValues.forEach { (field, maximum, createOptions) ->
            val failure = runCatching(createOptions).exceptionOrNull()

            failure.assert().isNotNull()
            failure!!.message.assert().contains(field, (maximum + 1).toString(), maximum.toString())
        }
    }

    @Test
    fun `should reject expansion depth below one`() {
        runCatching { BiScriptOptions(maxExpansionDepth = 0) }
            .isFailure
            .assert()
            .isTrue()
    }

    @Test
    fun `should expose structured diagnostics`() {
        val diagnostic = BiScriptDiagnostic(
            code = BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
            aggregate = "example.order",
            path = "items.product",
            sourceType = "example.Product",
            decision = BiScriptMappingDecision.MAX_DEPTH_RAW_JSON,
            message = "Max expansion depth reached.",
        )

        val result = BiScriptResult(
            script = "SELECT 1",
            statements = listOf("SELECT 1"),
            diagnostics = listOf(diagnostic),
        )

        result.script.assert().isEqualTo("SELECT 1")
        result.diagnostics.assert().containsExactly(diagnostic)
        BiScriptDiagnosticCode.entries.assert().containsExactly(
            BiScriptDiagnosticCode.RAW_JSON_FALLBACK,
            BiScriptDiagnosticCode.MAX_DEPTH_REACHED,
        )
        BiScriptMappingDecision.entries.assert().containsExactly(
            BiScriptMappingDecision.RAW_JSON,
            BiScriptMappingDecision.MAX_DEPTH_RAW_JSON,
        )
    }
}
