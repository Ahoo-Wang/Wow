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

package me.ahoo.wow.webflux.route.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiAggregateManifest
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.openapi.contract.bi.BiAggregateManifestContract
import me.ahoo.wow.openapi.contract.bi.BiDeploymentManifestContract
import me.ahoo.wow.openapi.contract.bi.BiScriptClusterRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptKafkaOffsetStorage
import me.ahoo.wow.openapi.contract.bi.BiScriptManifestContract
import me.ahoo.wow.openapi.contract.bi.BiScriptOperationMode
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyMode
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptUnsupportedTypeStrategy
import me.ahoo.wow.webflux.route.global.toBiScriptOperation
import me.ahoo.wow.webflux.route.global.toBiScriptOptions
import org.junit.jupiter.api.Test
import java.util.UUID

class BiScriptRequestMapperTest {
    @Test
    fun `should retain base options for an empty request`() {
        BiScriptRequest().toBiScriptOptions(BASE_OPTIONS).assert().isEqualTo(BASE_OPTIONS)
    }

    @Test
    fun `should override scalar options and inherit partial cluster values`() {
        val result = BiScriptRequest(
            database = "request_db",
            consumerDatabase = "request_consumer",
            topology = BiScriptTopologyRequest(
                mode = BiScriptTopologyMode.CLUSTER,
                cluster = BiScriptClusterRequest(name = "request-cluster"),
            ),
            timezone = "UTC",
            kafkaBootstrapServers = "request-kafka:9092",
            topicPrefix = "request.",
            maxExpansionDepth = 2,
            unsupportedTypeStrategy = BiScriptUnsupportedTypeStrategy.FAIL,
        ).toBiScriptOptions(BASE_OPTIONS)

        result.assert().isEqualTo(
            BiScriptOptions(
                database = "request_db",
                consumerDatabase = "request_consumer",
                topology = ClickHouseTopology.Cluster(
                    name = "request-cluster",
                    installation = "base-installation",
                ),
                timezone = "UTC",
                kafkaBootstrapServers = "request-kafka:9092",
                topicPrefix = "request.",
                maxExpansionDepth = 2,
                unsupportedTypeStrategy = UnsupportedTypeStrategy.FAIL,
            )
        )
    }

    @Test
    fun `should use cluster defaults when switching from standalone`() {
        val base = BASE_OPTIONS.copy(topology = ClickHouseTopology.Standalone)
        BiScriptRequest(
            topology = BiScriptTopologyRequest(
                mode = BiScriptTopologyMode.CLUSTER,
                cluster = BiScriptClusterRequest(name = "request-cluster"),
            )
        ).toBiScriptOptions(base).topology.assert().isEqualTo(
            ClickHouseTopology.Cluster(name = "request-cluster")
        )
    }

    @Test
    fun `should reject cluster details in standalone mode`() {
        runCatching {
            BiScriptRequest(
                topology = BiScriptTopologyRequest(
                    mode = BiScriptTopologyMode.STANDALONE,
                    cluster = BiScriptClusterRequest(name = "unused"),
                )
            ).toBiScriptOptions(BASE_OPTIONS)
        }.exceptionOrNull()!!.message.assert()
            .isEqualTo("topology.cluster must not be configured in STANDALONE mode")
    }

    @Test
    fun `should accept max expansion depth lower than the configured ceiling`() {
        BiScriptRequest(maxExpansionDepth = 2)
            .toBiScriptOptions(BASE_OPTIONS)
            .maxExpansionDepth.assert().isEqualTo(2)
    }

    @Test
    fun `should accept max expansion depth equal to the configured ceiling`() {
        BiScriptRequest(maxExpansionDepth = BASE_OPTIONS.maxExpansionDepth)
            .toBiScriptOptions(BASE_OPTIONS)
            .maxExpansionDepth.assert().isEqualTo(BASE_OPTIONS.maxExpansionDepth)
    }

    @Test
    fun `should reject max expansion depth above the configured ceiling`() {
        runCatching {
            BiScriptRequest(maxExpansionDepth = BASE_OPTIONS.maxExpansionDepth + 1)
                .toBiScriptOptions(BASE_OPTIONS)
        }.exceptionOrNull()!!.message.assert().isEqualTo(
            "maxExpansionDepth must be less than or equal to the configured maximum of 3"
        )
    }

    @Test
    fun `should map deploy manifest and reject tampered object names`() {
        val manifest = manifest()
        val operation = BiScriptRequest(previousManifest = manifest).toBiScriptOperation()
        (operation as BiScriptOperation.Deploy).previousManifest!!.aggregates.single()
            .tablePrefix.assert().isEqualTo("base_aggregate")

        runCatching {
            BiScriptRequest(
                previousManifest = manifest.copy(
                    aggregates = listOf(
                        manifest.aggregates.single().copy(tablePrefix = "unrelated_table")
                    )
                )
            ).toBiScriptOperation()
        }.exceptionOrNull()!!.message.assert().contains("does not match aggregate")
    }

    @Test
    fun `should require a manifest and explicit reset confirmation`() {
        val request = BiScriptRequest(
            operation = BiScriptOperationMode.RESET,
            previousManifest = manifest(),
            replayFromEarliestConfirmed = true,
        )
        val first = request.toBiScriptOperation() as BiScriptOperation.Reset

        first.replayFromEarliestConfirmed.assert().isTrue()
        first.previousManifest.consumerGeneration.assert().isEqualTo(manifest().consumerGeneration)
        first.previousManifest.retainedAggregates.map(BiAggregateManifest::aggregate)
            .assert().isEqualTo(listOf("old.aggregate"))
        runCatching {
            BiScriptRequest(operation = BiScriptOperationMode.RESET).toBiScriptOperation()
        }.exceptionOrNull()!!.message.assert().isEqualTo("previousManifest is required for RESET")
    }

    @Test
    fun `should reject operation-specific fields in the wrong mode`() {
        runCatching {
            BiScriptRequest(replayFromEarliestConfirmed = true).toBiScriptOperation()
        }.exceptionOrNull()!!.message.assert().isEqualTo("replayFromEarliestConfirmed is only valid for RESET")

        val resetWithoutConfirmation = BiScriptRequest(
            operation = BiScriptOperationMode.RESET,
            previousManifest = manifest(),
        )
        runCatching { resetWithoutConfirmation.toBiScriptOperation() }
            .exceptionOrNull()!!.message.assert()
            .isEqualTo("replayFromEarliestConfirmed is required for RESET")
    }

    private fun manifest(): BiScriptManifestContract = BiScriptManifestContract(
        formatVersion = 1,
        layoutVersion = 5,
        deployment = BiDeploymentManifestContract(
            database = "base_db",
            consumerDatabase = "base_consumer",
            topology = BiScriptTopologyRequest(
                mode = BiScriptTopologyMode.CLUSTER,
                cluster = BiScriptClusterRequest("base-cluster", "base-installation"),
            ),
            timezone = "Asia/Tokyo",
            kafkaBootstrapServers = "base-kafka:9092",
            topicPrefix = "base.",
            consumerGroupNamespace = "test",
            kafkaOffsetStorage = BiScriptKafkaOffsetStorage.BROKER,
            kafkaKeeperPathPrefix = "/wow/bi",
        ),
        consumerGeneration = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        aggregates = listOf(
            BiAggregateManifestContract(
                aggregate = "base.aggregate",
                tablePrefix = "base_aggregate",
                expansionViews = listOf("base_aggregate_state_last_root"),
            )
        ),
        retainedAggregates = listOf(
            BiAggregateManifestContract(
                aggregate = "old.aggregate",
                tablePrefix = "old_aggregate",
                expansionViews = listOf("old_aggregate_state_last_root"),
            )
        ),
    )

    private companion object {
        private val BASE_OPTIONS = BiScriptOptions(
            database = "base_db",
            consumerDatabase = "base_consumer",
            topology = ClickHouseTopology.Cluster(
                name = "base-cluster",
                installation = "base-installation",
            ),
            timezone = "Asia/Tokyo",
            kafkaBootstrapServers = "base-kafka:9092",
            topicPrefix = "base.",
            maxExpansionDepth = 3,
            unsupportedTypeStrategy = UnsupportedTypeStrategy.RAW_JSON,
        )
    }
}
