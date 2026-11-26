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

package me.ahoo.wow.ops.server

import kotlinx.coroutines.reactor.ReactorContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.schedule.ScheduleJobs
import me.ahoo.wow.cosec.CoSecCommandAuthorizer
import me.ahoo.wow.cosec.CoSecServiceRouteAuthorizer
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
import me.ahoo.wow.runtime.admission.RouteAction
import me.ahoo.wow.runtime.admission.ServiceRouteAuthorizer
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MapPropertySource
import reactor.util.context.Context
import java.util.function.Supplier

/**
 * `wow.cosec.authorizer.enabled` in an application where CoSec decides the requests itself (`cosec.pipeline.mode`
 * `enforce`, its default): its context built from the auto-configurations alone, as the class path lists them,
 * CoSec's starter's and the host's CoSec ones, with no bean of the application's for CoSec's checker, which CoSec's
 * pipeline defines after the host's configuration (#6849). The application's are its Manifest, its credentials (an
 * `Authenticator` taking `<principal>;<tenant>` bearer tokens) and its documents (CoSec's in-memory store with one
 * platform document); the request is alice's, as CoSec's pipeline permits it.
 */
@OptIn(ExperimentalWowApi::class, ExperimentalCoSecApi::class, InternalWowApi::class)
class CoSecDecidesHereTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application

    /** [use] of the application's context, the class path's other auto-configurations left out. */
    private fun application(use: (AnnotationConfigApplicationContext) -> Unit) {
        val loader = javaClass.classLoader
        val others = ImportCandidates.load(AutoConfiguration::class.java, loader).candidates
            .filterNot { it.startsWith("me.ahoo.cosec.") || it.startsWith("me.ahoo.wow.spring.boot.security.") }
        val settings = mapOf(
            "wow.cosec.authorizer.enabled" to "true",
            // The application's Authenticator takes the credentials.
            "cosec.jwt.enabled" to "false",
            "spring.autoconfigure.exclude" to others.joinToString(","),
        )
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("test", settings))
            context.registerBean(Manifest::class.java, Supplier { cartManifest() })
            context.registerBean(
                Authenticator::class.java,
                Supplier { Authenticator { credentials -> principalOf(credentials.token) } }
            )
            context.registerBean(SnapshotDocumentStore::class.java, Supplier { documents() })
            context.register(Application::class.java)
            context.refresh()
            use(context)
        }
    }

    @Test
    fun `CoSec's authorizers should be the node's, asking the checker CoSec's pipeline defines`(): Unit =
        application { context ->
            context.getBeansOfType(AuthorizationChecker::class.java).keys.assert()
                .containsExactly("cosecAuthorizationChecker")
            context.getBeansOfType(CommandAuthorizer::class.java).values.assert()
                .hasExactlyElementsOfTypes(CoSecCommandAuthorizer::class.java)
            context.getBeansOfType(ServiceRouteAuthorizer::class.java).values.assert()
                .hasExactlyElementsOfTypes(CoSecServiceRouteAuthorizer::class.java)
        }

    @Test
    fun `a command, a service route and an operational command should be decided by CoSec's policies`(): Unit =
        application { context ->
            val commands = CommandAuthorizer.allOf(
                context.getBeansOfType(CommandAuthorizer::class.java).values.toList()
            )
            val routes = ServiceRouteAuthorizer.allOf(
                context.getBeansOfType(ServiceRouteAuthorizer::class.java).values.toList(),
            )
            // The public port lets an operational command through only to an authorizer that decides them.
            commands.decidesOperational.assert().isTrue()
            val request = aliceRequest(context.getBean(Pipeline::class.java))
            runBlocking {
                inRequest(request) {
                    commands.authorize(ALICE, command("$SHOP.cart.AddItem", CART, "c-1")).assert()
                        .isEqualTo(Authorization.Allowed)
                    commands.authorize(ALICE, command("$SHOP.cart.RemoveItem", CART, "c-1")).assert()
                        .isInstanceOf(Authorization.Denied::class.java)
                    routes.authorize(ALICE, route("summary")).assert().isEqualTo(Authorization.Allowed)
                    routes.authorize(ALICE, route("audit")).assert().isInstanceOf(Authorization.Denied::class.java)
                    commands.authorize(ALICE, command(ScheduleJobs.ENABLE_JOB, SCHEDULE, "report")).assert()
                        .isEqualTo(Authorization.Allowed)
                    commands.authorize(ALICE, command(ScheduleJobs.DISABLE_JOB, SCHEDULE, "report")).assert()
                        .isInstanceOf(Authorization.Denied::class.java)
                    // A service's command on the internal port is no request of CoSec's: its own authorizer decides
                    // it, and CoSec's has no objection, whatever its policies say.
                    val service = Caller(tenantId = TENANT, scope = Scope("alice"), subject = "alice", service = true)
                    commands.authorize(service, command("$SHOP.cart.RemoveItem", CART, "c-1")).assert()
                        .isEqualTo(Authorization.Allowed)
                }
            }
        }

    @Test
    fun `a caller of the request that is not its principal should be denied, an operational command too`(): Unit =
        application { context ->
            // What an application's CallerResolver beside CoSec's gives when it maps the principal, or answers
            // anonymous callers: CoSec cannot evaluate it, so its authorizers deny it rather than abstain (D485 ⑦).
            val commands = CommandAuthorizer.allOf(
                context.getBeansOfType(CommandAuthorizer::class.java).values.toList()
            )
            val routes = ServiceRouteAuthorizer.allOf(
                context.getBeansOfType(ServiceRouteAuthorizer::class.java).values.toList(),
            )
            val request = aliceRequest(context.getBean(Pipeline::class.java))
            val foreign = listOf(ALICE.copyWith(subject = "user:alice"), Caller.of(TENANT))
            runBlocking {
                inRequest(request) {
                    foreign.forEach { caller ->
                        listOf(
                            commands.authorize(caller, command("$SHOP.cart.AddItem", CART, "c-1")),
                            commands.authorize(caller, command(ScheduleJobs.ENABLE_JOB, SCHEDULE, "report")),
                            routes.authorize(caller, route("summary")),
                        ).forEach { (it as Authorization.Denied).message.assert().startsWith("CoSec cannot authorize") }
                    }
                }
            }
        }

    /**
     * The security context CoSec's [pipeline] permits alice's `POST /shop/cart/c-1/add-item` in, once it has installed
     * the platform document, which it reads as it starts.
     */
    private fun aliceRequest(pipeline: Pipeline): SecurityContext {
        var permitted: SecurityContext? = null
        eventually("CoSec permits alice's request") {
            val outcome = runBlocking {
                pipeline.evaluate(
                    HttpFacts(
                        method = "POST",
                        rawPath = "/$SHOP/cart/c-1/add-item",
                        rawQuery = null,
                        headers = Headers.of(mapOf("Authorization" to listOf("Bearer alice;$TENANT"))),
                        cookies = Cookies.of(emptyMap()),
                        remoteAddress = "10.0.0.1",
                        host = HostScope(null),
                    ),
                )
            }
            permitted = outcome.security?.securityContext
            permitted != null
        }
        return checkNotNull(permitted)
    }

    /** [block] in the request CoSec's pipeline permitted: its filter writes it into the Reactor context. */
    private suspend fun <T> inRequest(security: SecurityContext, block: suspend () -> T): T =
        withContext(ReactorContext(Context.of(SecurityContext.KEY, security))) { block() }

    private fun command(name: String, type: AggregateType, id: String) = CommandAction(
        CommandType(name, Any::class.java),
        type.ref(TENANT, id),
        Scope("alice"),
        Creation.NONE,
    )

    private fun route(name: String) = RouteAction(SHOP, name, "GET", "/$SHOP/$name", TENANT, Scope("alice"))

    private companion object {
        const val SHOP = "shop"
        const val TENANT = "acme"
        val CART = AggregateType(SHOP, "cart")
        val SCHEDULE = AggregateType(SHOP, ScheduleJobs.AGGREGATE)
        val ALICE = Caller(tenantId = TENANT, scope = Scope("alice"), subject = "alice")

        /** The principal of a bearer token `<id>;<tenant>`, anonymous without one. */
        fun principalOf(token: String?): AuthnResult {
            val parts = token?.removePrefix("Bearer ")?.split(';') ?: return AuthnResult.Anonymous
            return AuthnResult.Authenticated(
                AuthenticatedPrincipal(
                    PrincipalId(parts[0]),
                    TenantId(parts[1]),
                    MembershipClaims.parse(emptyList(), emptyList()),
                    emptyMap(),
                ),
                Identity.Method("test"),
            )
        }

        /** The cart's command `AddItem`, whose route CoSec's pipeline maps to its action. */
        fun cartManifest(): Manifest = Manifest(
            contexts = listOf(
                ContextManifest(
                    name = SHOP,
                    aggregates = listOf(
                        AggregateManifest(
                            name = "cart",
                            state = StateManifest(Manifest.schemaRef("Cart")),
                            commands = listOf(
                                CommandManifest(
                                    name = "AddItem",
                                    schema = Manifest.schemaRef("AddItem"),
                                    routes = CommandRoutes.of(SHOP, "cart", "AddItem", Creation.NONE),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        /** One platform document: a cart's items added, the summary route, a job enabled; nothing else. */
        fun documents(): SnapshotDocumentStore = InMemorySnapshotDocumentStore().apply {
            put(
                SnapshotKey.Platform,
                SnapshotDocuments.read(
                    """
                    {
                      "schema": 2,
                      "revision": 1,
                      "publishedAt": "2026-11-24T00:00:00Z",
                      "policies": [{
                        "id": "shop",
                        "statements": [
                          { "name": "items added", "effect": "allow", "action": "shop:AddItem", "resource": "cart/*" },
                          { "name": "the summary", "effect": "allow", "action": "shop:summary", "resource": "route/summary" },
                          {
                            "name": "a job enabled",
                            "effect": "allow",
                            "action": "shop:wow.schedule.EnableJob",
                            "resource": "schedule/*"
                          }
                        ]
                      }],
                      "bindings": [{ "id": "shop", "subject": "*", "app": "*", "space": "*", "policy": "shop" }]
                    }
                    """.trimIndent().toByteArray(),
                ),
            )
        }
    }
}
