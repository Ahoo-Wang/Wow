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

import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.ClickHouseBiDeploymentInspector
import me.ahoo.wow.bi.NoOpBiDeploymentInspector
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.bi.BiClickHouseInspectorProperties
import me.ahoo.wow.spring.boot.starter.bi.BiScriptProperties
import me.ahoo.wow.spring.boot.starter.bi.toClientOptions
import me.ahoo.wow.spring.boot.starter.webflux.ConditionalOnWebfluxEnabled
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@AutoConfiguration(before = [WebFluxAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnWebfluxEnabled
@ConditionalOnProperty(
    prefix = BiScriptProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(BiScriptProperties::class)
class BiDeploymentInspectorAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(BiDeploymentInspector::class)
    @ConditionalOnProperty(
        prefix = "${BiScriptProperties.PREFIX}.inspector",
        name = ["type"],
        havingValue = "NO_OP",
        matchIfMissing = true,
    )
    fun noOpBiDeploymentInspector(): BiDeploymentInspector = NoOpBiDeploymentInspector

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["com.clickhouse.client.api.Client", "me.ahoo.wow.bi.ClickHouseBiDeploymentInspector"])
    @ConditionalOnProperty(
        prefix = "${BiScriptProperties.PREFIX}.inspector",
        name = ["type"],
        havingValue = "CLICKHOUSE",
    )
    class ClickHouseConfiguration {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(BiDeploymentInspector::class)
        fun clickHouseBiDeploymentInspector(
            biScriptProperties: BiScriptProperties,
        ): ClickHouseBiDeploymentInspector {
            val inspectorProperties = biScriptProperties.inspector
            inspectorProperties.timeout.validateTimeout("inspector.timeout")
            val properties = inspectorProperties.clickhouse
            properties.validate(inspectorProperties.timeout)
            return ClickHouseBiDeploymentInspector(
                clientOptions = properties.toClientOptions(),
                inspectionTimeout = inspectorProperties.timeout,
            )
        }
    }
}

private fun BiClickHouseInspectorProperties.validate(inspectionTimeout: java.time.Duration) {
    require(endpoints.isNotEmpty()) {
        "inspector.clickhouse.endpoints must not be empty when inspector.type=CLICKHOUSE"
    }
    endpoints.forEachIndexed { index, endpoint -> endpoint.validateEndpoint(index) }
    require(endpoints.distinct().size == endpoints.size) {
        "inspector.clickhouse.endpoints must not contain duplicates"
    }
    require(username.isNotBlank()) {
        "inspector.clickhouse.username must not be blank"
    }
    connectionTimeout.validateTimeout("inspector.clickhouse.connection-timeout")
    connectionRequestTimeout.validateTimeout("inspector.clickhouse.connection-request-timeout")
    socketTimeout.validateTimeout(
        property = "inspector.clickhouse.socket-timeout",
        maxMillis = Int.MAX_VALUE.toLong(),
    )
    require(socketTimeout <= inspectionTimeout) {
        "inspector.clickhouse.socket-timeout must not exceed inspector.timeout"
    }
    executionTimeout.validateTimeout(
        property = "inspector.clickhouse.execution-timeout",
        allowZero = true,
        maxMillis = Int.MAX_VALUE.toLong(),
    )
    require(maxConnections > 0) {
        "inspector.clickhouse.max-connections must be greater than zero"
    }
    require(maxRetries >= 0) {
        "inspector.clickhouse.max-retries must not be negative"
    }
}

private fun java.net.URI.validateEndpoint(index: Int) {
    require(scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) {
        "inspector.clickhouse.endpoints[$index] must use http or https"
    }
    require(!host.isNullOrBlank()) {
        "inspector.clickhouse.endpoints[$index] must contain a host"
    }
    require(port in 1..65535) {
        "inspector.clickhouse.endpoints[$index] must contain an explicit valid port"
    }
    require(userInfo == null && query == null && fragment == null) {
        "inspector.clickhouse.endpoints[$index] must not contain user info, query, or fragment"
    }
}

private fun java.time.Duration.validateTimeout(
    property: String,
    allowZero: Boolean = false,
    maxMillis: Long = Long.MAX_VALUE,
) {
    require(!isNegative && (allowZero || !isZero)) {
        if (allowZero) "$property must not be negative" else "$property must be greater than zero"
    }
    val millis = try {
        toMillis()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException(
            "$property is too large",
            error,
        )
    }
    require(millis <= maxMillis) {
        "$property must not exceed $maxMillis milliseconds"
    }
}
