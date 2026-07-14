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
import me.ahoo.wow.bi.BiDeploymentInspection
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.BiScriptOperation
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.bi.ClickHouseTopology
import me.ahoo.wow.bi.NoOpBiDeploymentInspector
import me.ahoo.wow.bi.UnsupportedTypeStrategy
import me.ahoo.wow.openapi.contract.bi.BiScriptClusterRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptOperationMode
import me.ahoo.wow.openapi.contract.bi.BiScriptRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyMode
import me.ahoo.wow.openapi.contract.bi.BiScriptTopologyRequest
import me.ahoo.wow.openapi.contract.bi.BiScriptUnsupportedTypeStrategy
import me.ahoo.wow.webflux.route.global.requireAllowedInspectionScope
import me.ahoo.wow.webflux.route.global.toBiScriptOperation
import me.ahoo.wow.webflux.route.global.toBiScriptOptions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

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
    fun `should reject catalog scope overrides for a fixed deployment inspector`() {
        val inspector = BiDeploymentInspector { _, _ -> Mono.just(BiDeploymentInspection.Unavailable) }
        val requests = listOf(
            BiScriptRequest(database = BASE_OPTIONS.database),
            BiScriptRequest(consumerDatabase = BASE_OPTIONS.consumerDatabase),
            BiScriptRequest(
                topology = BiScriptTopologyRequest(mode = BiScriptTopologyMode.STANDALONE)
            ),
        )

        requests.forEach { request ->
            runCatching { request.requireAllowedInspectionScope(inspector) }
                .exceptionOrNull()!!.message.assert().contains("overrides are not allowed")
        }
        BiScriptRequest(database = "preview").requireAllowedInspectionScope(NoOpBiDeploymentInspector)
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
    fun `should map deploy without caller supplied deployment history`() {
        BiScriptRequest().toBiScriptOperation().assert().isEqualTo(BiScriptOperation.Deploy)
    }

    @Test
    fun `should require explicit reset confirmation`() {
        val request = BiScriptRequest(
            operation = BiScriptOperationMode.RESET,
            replayFromEarliestConfirmed = true,
        )
        val first = request.toBiScriptOperation() as BiScriptOperation.Reset

        first.replayFromEarliestConfirmed.assert().isTrue()
        runCatching {
            BiScriptRequest(operation = BiScriptOperationMode.RESET).toBiScriptOperation()
        }.exceptionOrNull()!!.message.assert()
            .isEqualTo("replayFromEarliestConfirmed is required for RESET")
    }

    @Test
    fun `should reject operation-specific fields in the wrong mode`() {
        runCatching {
            BiScriptRequest(replayFromEarliestConfirmed = true).toBiScriptOperation()
        }.exceptionOrNull()!!.message.assert().isEqualTo("replayFromEarliestConfirmed is only valid for RESET")

        val resetWithoutConfirmation = BiScriptRequest(
            operation = BiScriptOperationMode.RESET,
        )
        runCatching { resetWithoutConfirmation.toBiScriptOperation() }
            .exceptionOrNull()!!.message.assert()
            .isEqualTo("replayFromEarliestConfirmed is required for RESET")
    }

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
