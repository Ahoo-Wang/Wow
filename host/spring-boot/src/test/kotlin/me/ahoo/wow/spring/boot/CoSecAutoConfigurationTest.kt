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

package me.ahoo.wow.spring.boot

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.SimpleAuthorization
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.webflux.ServerWebExchanges
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.cosec.CoSecCallerOptions
import me.ahoo.wow.cosec.CoSecCallerResolver
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecRequestFacts
import me.ahoo.wow.cosec.CoSecRequestIds
import me.ahoo.wow.http.WowHttpOptions
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.kernel.CommandType
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.CommandManifest
import me.ahoo.wow.manifest.CommandRoutes
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.runtime.admission.Authorization
import me.ahoo.wow.runtime.admission.CommandAction
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization
import me.ahoo.wow.manifest.Creation as ManifestCreation

class CoSecAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))

    @Configuration(proxyBeanMethods = false)
    class Application {
        @Bean
        fun callerResolver(): CallerResolver = CallerResolver.ANONYMOUS

        @Bean
        fun authorizer(): CommandAuthorizer = CommandAuthorizer.ALLOW_ALL
    }

    @Configuration(proxyBeanMethods = false)
    class CoSec {
        @Bean
        fun authorization(): CoSecAuthorization = CoSecAuthorization { _, _ -> Mono.just(AuthorizeResult.ALLOW) }

        /** The service's Manifest, which the host always has: CoSec's authorizer reads the command routes from it. */
        @Bean
        fun manifest(): Manifest = Manifest(contexts = emptyList())

        @Bean
        fun httpOptions(): WowHttpOptions = WowHttpOptions(requestFacts = setOf("X-Trace"))
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
            "wow.cosec.cross-scope.policies=p1, p2,",
            "wow.cosec.tenant-level.roles=employee",
            "wow.cosec.tenant-level.policies[0]=p3",
        ).run { context ->
            context.getBeansOfType(CallerResolver::class.java).values.single().assert()
                .isInstanceOf(CoSecCallerResolver::class.java)
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment
            ).assert().isEqualTo(
                CoSecCallerOptions(
                    "home",
                    setOf("admin", "ops"),
                    setOf("p1", "p2"),
                    tenantRoles = setOf("employee"),
                    tenantPolicies = setOf("p3"),
                ),
            )
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
    }

    @Test
    fun `the injected application is trusted only when the host turns it on, in CoSec's inject mode`() {
        val injectMode = arrayOf("cosec.inject.enabled=true", "cosec.authorization.enabled=false")
        runner.withPropertyValues(*injectMode).run { context ->
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment
            ).trustInjectedApp.assert().isFalse()
        }
        runner.withPropertyValues("wow.cosec.trust-injected-app=true", *injectMode).run { context ->
            CoSecAutoConfiguration.callerOptions(
                context.getBean(WowProperties::class.java),
                context.environment
            ).trustInjectedApp.assert().isTrue()
        }
    }

    @Test
    fun `trusting injected applications outside CoSec's inject mode fails the startup`() {
        listOf(
            arrayOf("wow.cosec.trust-injected-app=true"),
            arrayOf("wow.cosec.trust-injected-app=true", "cosec.inject.enabled=true"),
            arrayOf("wow.cosec.trust-injected-app=true", "cosec.authorization.enabled=false"),
        ).forEach { properties ->
            runner.withPropertyValues(*properties).run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence(context.startupFailure) { it.cause }
                    .any { it.message.orEmpty().contains("wow.cosec.trust-injected-app") }.assert().isTrue()
            }
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
    fun `the CoSec authorizer is opt-in and needs CoSec's authorization`() {
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
        runner.withPropertyValues("wow.cosec.authorizer.enabled=true").run { context ->
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
        runner.withUserConfiguration(CoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                context.getBean(CommandAuthorizer::class.java).assert().isInstanceOf(CoSecCommandAuthorizer::class.java)
            }
    }

    @Test
    fun `the CoSec request facts and request id join the edge's options, only with CoSec`() {
        runner.withUserConfiguration(CoSec::class.java).run { context ->
            val options = context.getBean(WowHttpOptions::class.java)
            options.requestFacts.assert().containsExactlyInAnyOrder("X-Trace", CoSecRequestFacts.DEVICE_ID)
            options.requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID, CoSecRequestIds.HEADER)
        }
        ApplicationContextRunner().withUserConfiguration(CoSec::class.java).run { context ->
            context.getBean(WowHttpOptions::class.java).requestIdHeaders.assert().containsExactly(WowHeaders.REQUEST_ID)
        }
    }

    @Test
    fun `the host imports the configuration before its own, only with CoSec on the classpath`() {
        val loader = CoSecAutoConfiguration::class.java.classLoader
        val candidates = ImportCandidates.load(AutoConfiguration::class.java, loader).candidates
        candidates.assert().contains(CoSecAutoConfiguration::class.java.name)
        val annotation = CoSecAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        annotation.before.toList().assert().containsExactly(WowAutoConfiguration::class)
        ApplicationContextRunner().withUserConfiguration(Imported::class.java).run { context ->
            context.getBeansOfType(CoSecCallerResolver::class.java).assert().hasSize(1)
        }
        ApplicationContextRunner().withUserConfiguration(Imported::class.java)
            .withClassLoader(FilteredClassLoader(ServerWebExchanges::class.java))
            .run { context -> context.getBeansOfType(CallerResolver::class.java).assert().isEmpty() }
    }

    /**
     * CoSec as a view store deploys it: no policies, and the application `console` whose role `editor` may do every
     * action. The Manifest's `CreateView` creates a view.
     */
    @Configuration(proxyBeanMethods = false)
    class AppRoleCoSec {
        private val all = PermissionData(id = "console.all", name = "all", action = AllActionMatcher.INSTANCE)
        private val permission = AppRolePermissionData(
            AppPermissionData("console", groups = listOf(PermissionGroupData("all", permissions = listOf(all)))),
            listOf(RolePermissionData("editor", setOf(all.id))),
        )

        @Bean
        fun authorization(): CoSecAuthorization = SimpleAuthorization(
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(emptyList())
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            },
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(
                    appId: String,
                    spaceId: String,
                    roleIds: Set<String>,
                ): Mono<AppRolePermission> = if (appId == "console") Mono.just(permission) else Mono.empty()
            },
        )

        @Bean
        fun manifest(): Manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = "view-store",
                    aggregates = listOf(
                        AggregateManifest(
                            name = "view",
                            state = StateManifest(Manifest.schemaRef("View")),
                            commands = listOf(
                                CommandManifest(
                                    name = "CreateView",
                                    schema = Manifest.schemaRef("CreateView"),
                                    creation = ManifestCreation.REQUIRED,
                                    routes = CommandRoutes.of(
                                        "view-store",
                                        "view",
                                        "CreateView",
                                        ManifestCreation.REQUIRED,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `with the switch on, CoSec allows a command by the app role permission of the caller's application`() {
        runner.withUserConfiguration(AppRoleCoSec::class.java)
            .withPropertyValues("wow.cosec.authorizer.enabled=true")
            .run { context ->
                val authorizer = context.getBean(CoSecCommandAuthorizer::class.java)
                val action = CommandAction(
                    CommandType("view-store.view.CreateView", Any::class.java),
                    AggregateRef(AggregateType("view-store", "view"), "acme", "v-1"),
                    Scope("alice", "s1"),
                    Creation.REQUIRED,
                )
                fun caller(app: String?) = Caller(
                    tenantId = "acme",
                    scope = Scope("alice", "s1"),
                    subject = "alice",
                    attributes = buildMap {
                        put(CoSecCallerResolver.ROLES, listOf("editor"))
                        app?.let { put(Caller.APP, listOf(it)) }
                    },
                )
                runBlocking {
                    authorizer.authorize(caller("console"), action).assert().isEqualTo(Authorization.Allowed)
                    authorizer.authorize(caller(null), action).assert().isInstanceOf(Authorization.Denied::class.java)
                }
            }
    }

    @Configuration(proxyBeanMethods = false)
    class CallCoSec {
        @Bean
        fun authorization(): CoSecAuthorization = CoSecAuthorization { _, _ -> Mono.just(AuthorizeResult.ALLOW) }
    }

    @Test
    fun `CoSec authorizes no service's call, a service names itself on the internal port (D300)`() {
        val runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecAutoConfiguration::class.java))
        runner.withUserConfiguration(CallCoSec::class.java).run { context ->
            // The host's own authorizer stays opt-in.
            context.getBeansOfType(CommandAuthorizer::class.java).assert().isEmpty()
        }
    }
}
