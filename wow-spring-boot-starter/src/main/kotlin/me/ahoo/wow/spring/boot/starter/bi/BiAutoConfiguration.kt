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

package me.ahoo.wow.spring.boot.starter.bi

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.bi.BiDeploymentInspectionException
import me.ahoo.wow.bi.BiDeploymentInspector
import me.ahoo.wow.bi.ClickHouseBiDeploymentInspector
import me.ahoo.wow.bi.NoOpBiDeploymentInspector
import me.ahoo.wow.openapi.catalog.RouteContributor
import me.ahoo.wow.openapi.contributor.global.GenerateBIScriptRouteContributor
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import me.ahoo.wow.spring.boot.starter.webflux.ConditionalOnWebfluxEnabled
import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.webflux.exception.ErrorHttpStatusMapping
import me.ahoo.wow.webflux.exception.RequestExceptionHandler
import me.ahoo.wow.webflux.route.HttpRouteHandlerFunctionFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import java.util.function.Predicate

/**
 * Wires the BI script route when `wow-bi` is on the classpath and `wow.bi.script.enabled` is not `false`.
 *
 * The route joins the router and the OpenAPI document through the starter's registration seams: its contract is a
 * [RouteContributor] bean, its handler an [HttpRouteHandlerFunctionFactory] bean, and its three error codes are
 * registered with [ErrorHttpStatusMapping]. Without `wow-bi` none of this exists, so the route is absent.
 */
@AutoConfiguration(after = [WebFluxAutoConfiguration::class])
@ConditionalOnWowEnabled
@ConditionalOnClass(name = ["me.ahoo.wow.bi.BiScriptGenerator"])
@ConditionalOnProperty(
    prefix = BiScriptProperties.PREFIX,
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(BiScriptProperties::class)
class BiAutoConfiguration {

    /** The route contract, offered to the route catalog and so to the OpenAPI document. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["me.ahoo.wow.openapi.RouterSpecs"])
    class RouteContractConfiguration {
        @Bean
        fun generateBIScriptRouteContributor(): RouteContributor = GenerateBIScriptRouteContributor
    }

    /**
     * The WebFlux side: the deployment inspector, and, when the WebFlux router is configured (this runs after
     * [WebFluxAutoConfiguration]), the route handler, which registers the error statuses.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebfluxEnabled
    @ConditionalOnClass(name = ["me.ahoo.wow.webflux.route.command.CommandHandlerFunction"])
    class WebFluxConfiguration {
        @Bean
        @ConditionalOnMissingBean(BiDeploymentInspector::class)
        @ConditionalOnProperty(
            prefix = "${BiScriptProperties.PREFIX}.inspector",
            name = ["type"],
            havingValue = "NO_OP",
            matchIfMissing = true,
        )
        fun noOpBiDeploymentInspector(): BiDeploymentInspector = NoOpBiDeploymentInspector

        @Bean
        @ConditionalOnBean(RequestExceptionHandler::class)
        fun generateBIScriptHandlerFunctionFactory(
            kafkaProperties: ObjectProvider<KafkaProperties>,
            biScriptProperties: BiScriptProperties,
            biDeploymentInspector: ObjectProvider<BiDeploymentInspector>,
            exceptionHandler: RequestExceptionHandler,
            biScriptAggregateExclusions: ObjectProvider<BiScriptAggregateExclusion>,
        ): HttpRouteHandlerFunctionFactory {
            // Registered with the handler, so the statuses exist whenever the route does.
            BiScriptErrorStatuses.register()
            val deploymentInspector = requireNotNull(biDeploymentInspector.getIfAvailable()) {
                "BiDeploymentInspector is required when wow.bi.script.enabled=true " +
                    "(inspector.type=${biScriptProperties.inspector.type})"
            }
            return GenerateBIScriptHandlerFunctionFactory(
                options = biScriptProperties.toBiScriptOptions(kafkaProperties.getIfAvailable()),
                deploymentInspector = deploymentInspector,
                exceptionHandler = exceptionHandler,
                aggregateFilter = biScriptAggregateExclusions.orderedStream().toList().toAggregateFilter(),
            )
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(
            name = ["com.clickhouse.client.api.Client", "me.ahoo.wow.bi.ClickHouseBiDeploymentInspector"]
        )
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
}

/** The HTTP statuses of the BI deployment inspection error codes. */
internal object BiScriptErrorStatuses {
    private val STATUSES = mapOf(
        BiDeploymentInspectionException.INCONSISTENT_ERROR_CODE to HttpStatus.BAD_GATEWAY,
        BiDeploymentInspectionException.UNAVAILABLE_ERROR_CODE to HttpStatus.SERVICE_UNAVAILABLE,
        BiDeploymentInspectionException.TIMEOUT_ERROR_CODE to HttpStatus.GATEWAY_TIMEOUT,
    )

    fun register() {
        STATUSES.forEach { (errorCode, status) -> ErrorHttpStatusMapping.register(errorCode, status) }
    }
}

private fun List<BiScriptAggregateExclusion>.toAggregateFilter(): Predicate<NamedAggregate> =
    Predicate { namedAggregate -> none { it.excludes(namedAggregate) } }

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
