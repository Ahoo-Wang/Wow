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

package me.ahoo.wow.webflux.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiConsumerIdentity
import me.ahoo.wow.bi.BiDeploymentDescriptor
import me.ahoo.wow.bi.BiDeploymentInspection
import me.ahoo.wow.bi.BiObjectKind
import me.ahoo.wow.bi.BiObjectMetadata
import me.ahoo.wow.bi.BiObjectMetadataCodec
import me.ahoo.wow.bi.BiScriptOptions
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ClickHouseBiDeploymentInspectorTest {
    @Test
    fun `should distinguish an authoritative empty catalog from unavailable inspection`() {
        val inspection = inspector("").inspect(OPTIONS).block() as BiDeploymentInspection.Available

        inspection.deployment.objects.assert().isEmpty()
    }

    @Test
    fun `should decode owned objects and validate the Kafka consumer identity`() {
        val identity = BiConsumerIdentity.deterministic(DESCRIPTOR)
        val metadata = BiObjectMetadata(
            deploymentId = DESCRIPTOR.deploymentId,
            configurationFingerprint = DESCRIPTOR.configurationFingerprint,
            aggregate = "example.order",
            kind = BiObjectKind.QUEUE,
            consumerIdentity = identity.value,
        )
        val body = JsonSerializer.writeValueAsString(
            mapOf(
                "database" to OPTIONS.consumerDatabase,
                "name" to "example_order_command_queue",
                "engine" to "Kafka",
                "engine_full" to "Kafka('kafka:9092', 'topic', " +
                    "'wow-bi.${identity.value}.example_order_command_consumer', 'JSONAsString')",
                "create_table_query" to "CREATE TABLE",
                "comment" to BiObjectMetadataCodec.encode(metadata),
            )
        )

        val available = inspector(body).inspect(OPTIONS).block() as BiDeploymentInspection.Available

        available.deployment.objects.single().metadata.assert().isEqualTo(metadata)
    }

    @Test
    fun `should fail when catalog replicas disagree`() {
        val first = catalogLine(engineFull = "View")
        val second = catalogLine(engineFull = "View from another replica")

        inspector("$first\n$second").inspect(OPTIONS).test()
            .expectErrorMatches { it is IllegalArgumentException && it.message!!.contains("differs across replicas") }
            .verify()
    }

    private fun catalogLine(engineFull: String): String = JsonSerializer.writeValueAsString(
        mapOf(
            "database" to OPTIONS.database,
            "name" to "foreign_view",
            "engine" to "View",
            "engine_full" to engineFull,
            "create_table_query" to "CREATE VIEW",
            "comment" to "",
        )
    )

    private fun inspector(body: String): ClickHouseBiDeploymentInspector {
        val webClient = WebClient.builder()
            .exchangeFunction {
                Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build())
            }
            .build()
        return ClickHouseBiDeploymentInspector(webClient)
    }

    private companion object {
        val OPTIONS = BiScriptOptions(consumerGroupNamespace = "test")
        val DESCRIPTOR = BiDeploymentDescriptor.from(OPTIONS)
    }
}
