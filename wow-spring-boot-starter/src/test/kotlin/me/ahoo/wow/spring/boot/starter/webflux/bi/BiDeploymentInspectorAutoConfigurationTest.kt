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

package me.ahoo.wow.spring.boot.starter.webflux.bi

import me.ahoo.test.asserts.assert
import me.ahoo.wow.bi.BiDeploymentInspection
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.ClickHouseBiDeploymentInspector
import me.ahoo.wow.bi.NoOpBiDeploymentInspector
import me.ahoo.wow.bi.ObservedBiDeployment
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.enableWow
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import java.time.Duration

class BiDeploymentInspectorAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .enableWow()
        .withUserConfiguration(BiDeploymentInspectorAutoConfiguration::class.java)

    @Test
    fun `should use the no-op inspector by default`() {
        contextRunner.run { context ->
            context.assert().hasNotFailed().hasSingleBean(BiDeploymentInspector::class.java)
            context.getBean(BiDeploymentInspector::class.java).assert().isSameAs(NoOpBiDeploymentInspector)
        }
    }

    @Test
    fun `should keep the no-op inspector available without the ClickHouse client`() {
        contextRunner
            .withClassLoader(FilteredClassLoader("com.clickhouse.client.api.Client"))
            .run { context ->
                context.assert().hasNotFailed().hasSingleBean(BiDeploymentInspector::class.java)
                context.getBean(BiDeploymentInspector::class.java).assert().isSameAs(NoOpBiDeploymentInspector)
            }
    }

    @Test
    fun `should explicitly configure the native ClickHouse inspector`() {
        contextRunner
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.inspector.type=CLICKHOUSE",
                "${BiScriptProperties.PREFIX}.inspector.timeout=4s",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.endpoints[0]=http://clickhouse-1:8123/",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.endpoints[1]=https://clickhouse-2:8443/proxy",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.username=bi-user",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.password=secret",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.connection-pool-enabled=false",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.connection-timeout=2s",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.connection-request-timeout=3s",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.socket-timeout=4s",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.execution-timeout=5s",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.max-connections=12",
                "${BiScriptProperties.PREFIX}.inspector.clickhouse.max-retries=2",
            )
            .run { context ->
                context.assert().hasNotFailed().hasSingleBean(BiDeploymentInspector::class.java)
                context.getBean(BiDeploymentInspector::class.java).assert()
                    .isInstanceOf(ClickHouseBiDeploymentInspector::class.java)

                val inspector = context.getBean(BiScriptProperties::class.java).inspector
                inspector.timeout.assert().isEqualTo(Duration.ofSeconds(4))
                val properties = inspector.clickhouse
                properties.endpoints.assert().hasSize(2)
                properties.username.assert().isEqualTo("bi-user")
                properties.connectionPoolEnabled.assert().isFalse()
                properties.connectionTimeout.assert().isEqualTo(Duration.ofSeconds(2))
                properties.connectionRequestTimeout.assert().isEqualTo(Duration.ofSeconds(3))
                properties.socketTimeout.assert().isEqualTo(Duration.ofSeconds(4))
                properties.executionTimeout.assert().isEqualTo(Duration.ofSeconds(5))
                properties.maxConnections.assert().isEqualTo(12)
                properties.maxRetries.assert().isEqualTo(2)
                properties.toString().assert().contains("password=******").doesNotContain("secret")
            }
    }

    @Test
    fun `should not initialize an inspector when BI script generation is disabled`() {
        contextRunner
            .withPropertyValues(
                "${BiScriptProperties.PREFIX}.enabled=false",
                "${BiScriptProperties.PREFIX}.inspector.type=CLICKHOUSE",
            )
            .run { context ->
                context.assert().hasNotFailed().doesNotHaveBean(BiDeploymentInspector::class.java)
            }
    }

    @Test
    fun `should fail startup when ClickHouse inspection lacks endpoints`() {
        contextRunner
            .withPropertyValues("${BiScriptProperties.PREFIX}.inspector.type=CLICKHOUSE")
            .run { context ->
                context.startupFailure.assert().isNotNull()
                context.startupFailure!!.causeChainMessages().assert()
                    .contains("inspector.clickhouse.endpoints must not be empty when inspector.type=CLICKHOUSE")
            }
    }

    @Test
    fun `should reject unsafe ClickHouse inspector configuration`() {
        val invalidConfigurations = listOf(
            "inspector.clickhouse.endpoints[0]=jdbc:clickhouse://localhost:8123" to
                "inspector.clickhouse.endpoints[0] must use http or https",
            "inspector.clickhouse.endpoints[0]=http://clickhouse" to
                "inspector.clickhouse.endpoints[0] must contain an explicit valid port",
            "inspector.timeout=0s" to
                "inspector.timeout must be greater than zero",
            "inspector.clickhouse.connection-timeout=0s" to
                "inspector.clickhouse.connection-timeout must be greater than zero",
            "inspector.clickhouse.connection-request-timeout=0s" to
                "inspector.clickhouse.connection-request-timeout must be greater than zero",
            "inspector.clickhouse.max-connections=0" to
                "inspector.clickhouse.max-connections must be greater than zero",
            "inspector.clickhouse.max-retries=-1" to
                "inspector.clickhouse.max-retries must not be negative",
        )
        invalidConfigurations.forEach { (property, expectedMessage) ->
            contextRunner
                .withPropertyValues(
                    "${BiScriptProperties.PREFIX}.inspector.type=CLICKHOUSE",
                    "${BiScriptProperties.PREFIX}.inspector.clickhouse.endpoints[0]=http://clickhouse:8123",
                    "${BiScriptProperties.PREFIX}.$property",
                )
                .run { context ->
                    context.startupFailure.assert().isNotNull()
                    context.startupFailure!!.causeChainMessages().assert().contains(expectedMessage)
                }
        }
    }

    @Test
    fun `should let a custom inspector override ClickHouse configuration`() {
        val customInspector = BiDeploymentInspector {
            Mono.just(BiDeploymentInspection.Available(ObservedBiDeployment(emptyList())))
        }
        contextRunner
            .withBean(BiDeploymentInspector::class.java, { customInspector })
            .withPropertyValues("${BiScriptProperties.PREFIX}.inspector.type=CLICKHOUSE")
            .run { context ->
                context.assert().hasNotFailed().hasSingleBean(BiDeploymentInspector::class.java)
                context.getBean(BiDeploymentInspector::class.java).assert().isSameAs(customInspector)
            }
    }

    private fun Throwable.causeChainMessages(): String =
        generateSequence(this) { throwable -> throwable.cause }
            .mapNotNull(Throwable::message)
            .joinToString("\n")
}
