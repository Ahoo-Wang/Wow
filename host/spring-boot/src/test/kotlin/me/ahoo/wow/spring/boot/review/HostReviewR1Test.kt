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
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.security.CoSecAutoConfiguration
import me.ahoo.wow.spring.boot.security.CoSecRouteMapAutoConfiguration
import me.ahoo.wow.spring.boot.store.DataSourceAutoConfiguration
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

    private companion object {
        const val SECRET = "s3cret-pw"
    }
}
