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

package me.ahoo.wow.spring.boot.review

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.mockk
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.test.asserts.assert
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.spi.config.ProviderSettings
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.transport.TopicTransportOptions
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.security.CoSecAutoConfiguration
import me.ahoo.wow.spring.boot.security.CoSecRouteMapAutoConfiguration
import me.ahoo.wow.spring.boot.startHost
import me.ahoo.wow.spring.boot.store.DataSourceAutoConfiguration
import me.ahoo.wow.spring.boot.transport.PortProviders
import me.ahoo.wow.spring.boot.transport.TransportNeeds
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import tools.jackson.databind.json.JsonMapper
import javax.sql.DataSource

/**
 * Deep review round 1 of the Spring Boot host, configuration and observability: each case fails on main, and moves to
 * the class whose promise it is when its issue is fixed.
 */
class HostReviewR1Test {
    @Test
    fun `the host's DataSource should never log a password its JDBC URL carries`() {
        // PostgreSQL's driver takes the credentials as URL parameters as well as from username and password.
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val lines = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(lines)
        try {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration::class.java))
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://db:5432/shop?user=shop&password=$SECRET")
                .run { context -> context.getBean(DataSource::class.java) }
        } finally {
            root.detachAppender(lines)
        }
        val logged = lines.list.map { it.formattedMessage }
        logged.filter { "spring.datasource.url" in it }.assert().isNotEmpty()
        logged.filter { SECRET in it }.assert().isEmpty()
    }

    /**
     * CoSec's pipeline, which defines its `AuthorizationChecker` where CoSec decides the requests itself, stood in by
     * an auto-configuration in its place: after Wow's route map, which is `beforeName` CoSec's
     * `CoSecPipelineAutoConfiguration` and after [WowAutoConfiguration], which [CoSecAutoConfiguration] is before.
     */
    @AutoConfiguration(after = [CoSecRouteMapAutoConfiguration::class])
    class CoSecPipelineStandIn {
        @Bean
        fun cosecAuthorizationChecker(): AuthorizationChecker = mockk()
    }

    @Configuration(proxyBeanMethods = false)
    class Application {
        @Bean
        fun manifest(): Manifest = Manifest()
    }

    @Test
    fun `wow-cosec-authorizer-enabled should make CoSec's authorizers the node's where CoSec's pipeline decides`() {
        val configurations = AutoConfigurations.of(
            CoSecPipelineStandIn::class.java,
            CoSecRouteMapAutoConfiguration::class.java,
            CoSecAutoConfiguration::class.java,
        )
        // As in an application: CoSec's pipeline, and its checker, come after Wow's CoSec configuration.
        Configurations.getClasses(configurations).map { it.simpleName }.assert()
            .containsSubsequence(CoSecAutoConfiguration::class.simpleName, CoSecPipelineStandIn::class.simpleName)
        ApplicationContextRunner()
            .withConfiguration(configurations)
            .withUserConfiguration(Application::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                context.getBeansOfType(AuthorizationChecker::class.java).assert().hasSize(1)
                context.getBeansOfType(CommandAuthorizer::class.java).values.assert()
                    .hasAtLeastOneElementOfType(CoSecCommandAuthorizer::class.java)
            }
    }

    @Test
    fun `a provider's option set by an environment variable should be read, as the key check and the needs read it`() {
        // As Spring Boot attaches an application's environment, the operating system's variables in it.
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                "systemEnvironment",
                mapOf<String, Any>("WOW_KAFKA_COMMANDINTAKE_ENABLED" to "true", "WOW_KAFKA_TOPICPREFIX" to "shop."),
            ),
        )
        ConfigurationPropertySources.attach(environment)
        // The node's needs read the variable as wow.kafka.command-intake.enabled: the intake is on.
        TransportNeeds.intake(environment, listOf("kafka")).assert().containsExactly("wow.kafka.command-intake.enabled")
        // The transport's settings, as the host opens the transport with them (PortProviders.transportConfigs).
        val settings = ProviderSettings(
            "wow.kafka",
            PortProviders.declared(PortProviders.optionsOf(environment, "kafka"), TopicTransportOptions.ALL),
        )
        settings.option(TopicTransportOptions.COMMAND_INTAKE).assert().isTrue()
        settings.option(TopicTransportOptions.TOPIC_PREFIX).assert().isEqualTo("shop.")
    }

    @Test
    fun `a mistyped security key should refuse the start under lazy initialization as without it`() {
        val failure = runCatching {
            startHost("spring.main.lazy-initialization" to true, "wow.cosec.authorizer.enable" to true).close()
        }.exceptionOrNull()
        generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().firstOrNull()?.message
            .assert().contains("Unknown Wow configuration keys: wow.cosec.authorizer.enable.")
    }

    @Test
    fun `a liveness stall timeout not longer than the reconcile timeout should refuse the start, naming its key`() {
        // A stuck reconcile must make the node leave the members (reconcile-timeout, 1 minute) before its liveness
        // fails and the platform restarts it, as both keys' descriptions say; else it restarts holding its leases.
        val failure = runCatching { startHost("wow.health.liveness.stall-timeout" to "30s").close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().firstOrNull()?.key
            .assert().isEqualTo("wow.health.liveness.stall-timeout")
    }

    @Test
    fun `a negative dataset query bound should refuse the start, as a store's does, not mean no bound`() {
        val failure = runCatching { startHost("wow.query.dataset.max-concurrent" to -1).close() }.exceptionOrNull()
        generateSequence(failure) { it.cause }.filterIsInstance<WowConfigurationException>().firstOrNull()?.key
            .assert().isEqualTo("wow.query.dataset.max-concurrent")
    }

    @Test
    fun `the configuration metadata should give the shared states' size bound its default, as the reference shows`() {
        val metadata = JsonMapper.builder().build().readTree(
            javaClass.getResource("/META-INF/spring-configuration-metadata.json")!!.readText(),
        )
        val maxBytes = metadata["properties"].single { it["name"].asString() == "wow.shared-states.max-bytes" }
        maxBytes["defaultValue"]?.asString().assert().isEqualTo("64MB")
    }

    private companion object {
        const val SECRET = "s3cret-pw"
    }
}
