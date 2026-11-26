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

package me.ahoo.wow.spring.boot.security

import io.mockk.mockk
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.document.route.RouteResolution
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.http.ProblemRenderer
import me.ahoo.cosec.webflux.ServerWebExchanges
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.cosec.CoSecCallerOptions
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecProblemRenderer
import me.ahoo.wow.cosec.CoSecRequestIds
import me.ahoo.wow.cosec.CoSecServiceRouteAuthorizer
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import me.ahoo.wow.spring.boot.WowAutoConfiguration
import me.ahoo.wow.spring.boot.WowProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
@ExtendWith(OutputCaptureExtension::class)
class CoSecAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(CoSecAutoConfiguration::class.java, CoSecAuthorizersAutoConfiguration::class.java),
        )

    @Configuration(proxyBeanMethods = false)
    class Application {
        @Bean
        fun callerResolver(): CallerResolver = CallerResolver.ANONYMOUS

        @Bean
        fun authorizer(): CommandAuthorizer = CommandAuthorizer.ALLOW_ALL
    }

    /** What CoSec's starter defines where it decides the requests itself: its checker; and the host's Manifest. */
    @Configuration(proxyBeanMethods = false)
    class CoSec {
        @Bean
        fun checker(): AuthorizationChecker = mockk()

        @Bean
        fun manifest(): Manifest = cartManifest()

        @Bean
        fun httpOptions(): WowHttpOptions = WowHttpOptions(requestFacts = setOf("X-Trace"))
    }

    @Configuration(proxyBeanMethods = false)
    class OwnRoutes {
        @Bean
        fun routes(): RouteMap = RouteMap.EMPTY
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(CoSecAutoConfiguration::class)
    class Imported

    @Test
    fun `the CoSec resolver is the host's caller resolver, configured from wow properties`() {
        runner.withPropertyValues(
            "wow.tenant.default=home",
            "wow.cosec.cross-scope.roles[0]=admin",
            "wow.cosec.cross-scope.roles[1]=ops",
            "wow.cosec.tenant-level.roles=employee, staff,",
        ).run { context ->
            context.getBeansOfType(CallerResolver::class.java).values.single().assert()
                .isInstanceOf(CoSecCallerResolver::class.java)
            CoSecAutoConfiguration.callerOptions(context.getBean(WowProperties::class.java)).assert().isEqualTo(
                CoSecCallerOptions("home", setOf("admin", "ops"), tenantRoles = setOf("employee", "staff")),
            )
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `the application's own resolver wins, and its authorizer is asked beside CoSec's`() {
        runner.withUserConfiguration(Application::class.java, CoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                context.getBean(CallerResolver::class.java).assert().isSameAs(CallerResolver.ANONYMOUS)
                // Both are the node's authorizers: each must allow a command.
                context.getBeansOfType(CommandAuthorizer::class.java).values.assert()
                    .contains(CommandAuthorizer.ALLOW_ALL)
                    .anyMatch { it is CoSecCommandAuthorizer }
            }
    }

    @Test
    fun `CoSec's refusals are written by CoSec unless wow-cosec-problems is wow, and an application's renderer wins`(
        output: CapturedOutput,
    ) {
        val renderers = { context: AssertableApplicationContext ->
            context.getBeansOfType(ProblemRenderer::class.java).mapValues { it.value.javaClass }
        }
        runner.run { context -> renderers(context).assert().isEmpty() }
        runner.withPropertyValues("${CoSecAutoConfiguration.PROBLEMS}=cosec").run { context ->
            renderers(context).assert().isEmpty()
        }
        runner.withPropertyValues("${CoSecAutoConfiguration.PROBLEMS}=wow").run { context ->
            renderers(context).assert().isEqualTo(mapOf("wowCoSecProblemRenderer" to CoSecProblemRenderer::class.java))
        }
        output.out.assert().contains("CoSec's refusals are written as Wow's problems (wow.cosec.problems=wow).")
        runner.withUserConfiguration(OwnRenderer::class.java)
            .withPropertyValues("${CoSecAutoConfiguration.PROBLEMS}=wow")
            .run { context -> renderers(context).keys.assert().containsExactly("ownRenderer") }
        output.out.assert()
            .contains("wow.cosec.problems is wow, but the application declares its own ProblemRenderer ownRenderer")
    }

    /** An application's own renderer of CoSec's refusals. */
    @Configuration(proxyBeanMethods = false)
    class OwnRenderer {
        @Bean
        fun ownRenderer(): ProblemRenderer = ProblemRenderer.DEFAULT
    }

    @Test
    fun `the CoSec authorizers are opt-in and need CoSec deciding here`() {
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
            context.getBeansOfType(ServiceRouteAuthorizer::class.java).assert().isEmpty()
        }
        // Behind a CoSec gateway there is no checker: authorization is the gateway's.
        runner.withPropertyValues("wow.cosec.authorizer.enabled=true").run { context ->
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
        runner.withUserConfiguration(CoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                context.getBean(CommandAuthorizer::class.java).assert().isInstanceOf(CoSecCommandAuthorizer::class.java)
                context.getBean(ServiceRouteAuthorizer::class.java).assert()
                    .isInstanceOf(CoSecServiceRouteAuthorizer::class.java)
            }
    }

    @Test
    fun `CoSec's route map is the Manifest's routes where CoSec decides the requests itself`() {
        val runner = ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(CoSecAutoConfiguration::class.java, CoSecRouteMapAutoConfiguration::class.java)
        )
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            val mapped = context.getBean(RouteMap::class.java).resolve("POST", "/shop/cart/c-1/add-item")
                as RouteResolution.Mapped
            "${mapped.action.namespace}:${mapped.action.verb} ${mapped.resource.type}/${mapped.resource.id}".assert()
                .isEqualTo("shop:AddItem cart/c-1")
        }
        runner.withUserConfiguration(
            CoSec::class.java
        ).withPropertyValues("cosec.pipeline.mode=gateway").run { context ->
            context.getBeansOfType(RouteMap::class.java).assert().isEmpty()
        }
        runner.withUserConfiguration(CoSec::class.java).withPropertyValues("cosec.enabled=false").run { context ->
            context.getBeansOfType(RouteMap::class.java).assert().isEmpty()
        }
        runner.withUserConfiguration(CoSec::class.java, OwnRoutes::class.java).run { context ->
            context.getBean(RouteMap::class.java).assert().isSameAs(RouteMap.EMPTY)
        }
    }

    @Test
    fun `CoSec's request id joins the edge's options, only with CoSec, and no request fact of CoSec's does`() {
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            val options = context.getBean(WowHttpOptions::class.java)
            // The device id is recorded only when the application lists it (#7076).
            options.requestFacts.assert().containsExactly("X-Trace")
            options.requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID, CoSecRequestIds.HEADER)
        }
        // CoSec's modules on the class path, CoSec off (wow.cosec.mode: disabled): no header of CoSec's is read.
        runner.withUserConfiguration(CoSec::class.java).withPropertyValues("cosec.enabled=false").run { context ->
            val options = context.getBean(WowHttpOptions::class.java)
            options.requestFacts.assert().containsExactly("X-Trace")
            options.requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID)
        }
        ApplicationContextRunner().withUserConfiguration(CoSec::class.java).run { context ->
            context.getBean(WowHttpOptions::class.java).requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID)
        }
    }

    @Test
    fun `the host imports the configurations around its own and CoSec's pipeline, only with CoSec on the classpath`() {
        val loader = CoSecAutoConfiguration::class.java.classLoader
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, loader).candidates
        candidates.assert().contains(
            CoSecAutoConfiguration::class.java.name,
            CoSecRouteMapAutoConfiguration::class.java.name,
            CoSecAuthorizersAutoConfiguration::class.java.name,
        )
        val annotation = CoSecAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        annotation.before.toList().assert().containsExactly(WowAutoConfiguration::class)
        val routes = CoSecRouteMapAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        routes.after.toList().assert().containsExactly(WowAutoConfiguration::class)
        routes.beforeName.toList().assert().containsExactly(COSEC_PIPELINE_AUTO_CONFIGURATION)
        // The authorizers ask the checker CoSec's pipeline defines: after it, so their condition sees it (#6849); an
        // application's context built from the auto-configurations alone is the ops server's CoSecDecidesHereTest.
        val authorizers = CoSecAuthorizersAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        authorizers.after.toList().assert().containsExactly(WowAutoConfiguration::class)
        authorizers.afterName.toList().assert().containsExactly(COSEC_PIPELINE_AUTO_CONFIGURATION)
        ApplicationContextRunner().withUserConfiguration(Imported::class.java).run { context ->
            context.getBeansOfType(CoSecCallerResolver::class.java).assert().hasSize(1)
        }
        ApplicationContextRunner().withUserConfiguration(Imported::class.java)
            .withClassLoader(FilteredClassLoader(ServerWebExchanges::class.java))
            .run { context -> context.getBeansOfType(CallerResolver::class.java).assert().isEmpty() }
    }

    private companion object {
        fun cartManifest(): Manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "shop",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "cart",
                            state = StateManifest(Manifest.schemaRef("Cart")),
                            commands = listOf(
                                CommandManifest(
                                    name = "AddItem",
                                    schema = Manifest.schemaRef("AddItem"),
                                    routes = CommandRoutes.of("shop", "cart", "AddItem", Creation.NONE),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
