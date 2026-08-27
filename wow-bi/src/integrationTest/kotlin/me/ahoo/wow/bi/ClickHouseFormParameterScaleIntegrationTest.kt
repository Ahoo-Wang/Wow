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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.testcontainers.clickhouse.ClickHouseContainer
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "WOW_BI_CATALOG_SCALE", matches = "(?i:true|1)")
class ClickHouseFormParameterScaleIntegrationTest {
    private lateinit var clickHouse: ClickHouseContainer

    @BeforeAll
    fun startClickHouse() {
        clickHouse = ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_IMAGE))
        clickHouse.start()
    }

    @AfterAll
    fun closeClickHouse() {
        if (::clickHouse.isInitialized) {
            clickHouse.close()
        }
    }

    @Test
    fun `should accept large desired-name arrays through ClickHouse form parameters`() {
        NativeClickHouseCatalogClient.create(clientOptions()).use { client ->
            val probes = FORM_PARAMETER_SIZES.map { size ->
                probe(client, size)
            }
            writeReport(
                probes.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { it.toJson() },
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the failed probe size while retaining the original client error.
    private fun probe(client: ClickHouseCatalogClient, size: Int): FormParameterProbe {
        val names = List(size) { index ->
            if (index == 0) {
                SENTINEL
            } else {
                "desired_${index.toString().padStart(5, '0')}"
            }
        }
        val parameterPlan = ClickHouseStringArrayParameterPlan.create(
            expression = "name",
            parameterName = "names",
            values = names,
        )
        val payloadBytes = parameterPlan.parameters.values.sumOf { value ->
            value.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val maxParameterBytes = parameterPlan.parameters.values.maxOf { value ->
            value.toByteArray(Charsets.UTF_8).size
        }
        val lengthExpression = parameterPlan.parameters.keys.joinToString(" + ") { name ->
            "length({$name:Array(String)})"
        }
        val firstParameterName = parameterPlan.parameters.keys.first()
        val startedAt = System.nanoTime()
        val rows = try {
            client.query(
                sql = """
                    SELECT throwIf(
                        $lengthExpression != {expected:UInt64}
                            OR {$firstParameterName:Array(String)}[1] != {sentinel:String},
                        'ClickHouse form parameter content mismatch'
                    ) AS accepted
                """.trimIndent(),
                parameters = parameterPlan.parameters + mapOf(
                    "expected" to size.toLong(),
                    "sentinel" to SENTINEL,
                ),
                columns = listOf("accepted"),
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                "ClickHouse form parameter probe failed at size [$size], payloadBytes [$payloadBytes]",
                error,
            )
        }
        rows.assert().hasSize(1)
        return FormParameterProbe(
            size = size,
            payloadBytes = payloadBytes,
            parameterCount = parameterPlan.parameters.size,
            maxParameterBytes = maxParameterBytes,
            latencyMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
        )
    }

    private fun clientOptions(): ClickHouseClientOptions = ClickHouseClientOptions(
        endpoints = listOf(URI.create(clickHouse.httpUrl)),
        username = clickHouse.username,
        password = clickHouse.password,
        connectionTimeout = Duration.ofSeconds(5),
        connectionRequestTimeout = Duration.ofSeconds(30),
        socketTimeout = Duration.ofSeconds(30),
        executionTimeout = Duration.ofSeconds(30),
    )

    private fun writeReport(content: String) {
        val reportDirectory = Path.of("build", "reports", "wow-bi", "catalog-scale")
        Files.createDirectories(reportDirectory)
        Files.writeString(
            reportDirectory.resolve("form-parameter-scale.json"),
            content + System.lineSeparator(),
        )
    }

    private data class FormParameterProbe(
        val size: Int,
        val payloadBytes: Long,
        val parameterCount: Int,
        val maxParameterBytes: Int,
        val latencyMillis: Long,
    ) {
        fun toJson(): String =
            "  {\"size\":$size,\"payloadBytes\":$payloadBytes," +
                "\"parameterCount\":$parameterCount,\"maxParameterBytes\":$maxParameterBytes," +
                "\"latencyMillis\":$latencyMillis}"
    }

    private companion object {
        const val CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:24.8.14.39-alpine"
        const val SENTINEL = "desired_'\\_你好"
        val FORM_PARAMETER_SIZES = listOf(1_000, 5_000, 10_000)
    }
}
