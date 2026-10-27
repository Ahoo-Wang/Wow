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

package me.ahoo.wow.viewstore.server

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.jwt.JwtTokenConverter
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.TokenConverter
import me.ahoo.cosid.jvm.UuidGenerator
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Mono
import java.util.UUID
import me.ahoo.cosec.api.permission.AppRolePermission as CoSecAppRolePermission

/**
 * The view store in its production shape, the `cosec` profile (roadmap P.15), end to end over HTTP: the token is the
 * gateway's ([tokens]), the user is its principal, and every view is kept in the application CoSec authorized the
 * request in (decisions D232, D236), never in the `CoSec-App-Id` a client merely sends.
 */
abstract class ViewStoreCoSecContract {
    protected abstract val store: ViewStoreNode

    /**
     * The tokens a CoSec deployment issues. Behind the gateway the service holds no secret and verifies none (decision
     * D296); a service that authorizes its requests itself verifies them with [SECRET].
     */
    private val tokens: TokenConverter = JwtTokenConverter(UuidGenerator.INSTANCE, Algorithm.HMAC256(SECRET))

    @AfterAll
    fun closeStore() {
        store.close()
    }

    /** [id] signed in with [roles], calling from [app]. */
    protected fun signedIn(id: String, vararg roles: String, app: String = APP): Who {
        val principal = SimpleTenantPrincipal(SimplePrincipal(id, roles = roles.toSet()), SimpleTenant("(0)"))
        return Who(
            mapOf(
                AUTHORIZATION to "Bearer ${tokens.toToken(principal).accessToken}",
                ViewStoreContext.APP_ID_HEADER to app,
            ),
        )
    }

    protected fun definition(): String = "orders-${UUID.randomUUID()}"

    protected fun create(who: Who, definition: String): Answer =
        store.post(VIEWS, """{"definitionId":"$definition","title":"Orders","config":$RECORD}""", who)

    protected fun list(who: Who, definition: String): Answer = store.post(
        VIEW_QUERY,
        """{"kind":"list","filter":{"op":"EQ","field":"state.definitionId","value":"$definition"}}""",
        who,
    )

    @Test
    fun `a signed-in user should keep views in the application CoSec authorized, and read only its own`() {
        val definition = definition()
        val alice = signedIn("alice", USER_ROLE)
        val id = create(alice, definition).assertOk().id
        val view = store.post(VIEW_QUERY, """{"kind":"single","filter":{"op":"ID","value":"$id"}}""", alice)
            .assertOk().json
        view["state"]["appId"].asString().assert().isEqualTo(APP)
        view["ownerId"].asString().assert().isEqualTo("alice")
        list(alice, definition).assertOk().ids.assert().containsExactly(id)
        list(signedIn("bob", USER_ROLE), definition).assertOk().ids.assert().isEmpty()
    }

    @Test
    fun `a user should read the shared views, which only a shared writer writes`() {
        val definition = definition()
        val carol = signedIn("carol", USER_ROLE, ViewStoreRoles.SHARED_WRITER)
        val shared = create(carol.shared, definition).assertOk().id
        val alice = signedIn("alice", USER_ROLE)
        list(alice.shared, definition).assertOk().ids.assert().containsExactly(shared)
        store.post("/view-store/view/$shared/rename-view", """{"title":"Mine"}""", alice.shared)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        // Acting as another user is refused: only the reserved owners are open to Wow-Scope.
        list(alice.with(WowHeaders.SCOPE, "owner=bob"), definition).assertProblem(FORBIDDEN, "wow.auth.forbidden")
    }

    @Test
    fun `a request without a token should be answered 401`() {
        val app = Who(mapOf(ViewStoreContext.APP_ID_HEADER to APP))
        create(app, definition()).status.assert().isEqualTo(UNAUTHORIZED)
        list(app, definition()).status.assert().isEqualTo(UNAUTHORIZED)
    }

    protected companion object {
        const val SECRET = "the-view-store-test-secret-of-32-characters"
        const val AUTHORIZATION = "Authorization"
        const val APP = "orders-console"
        const val USER_ROLE = "orders-user"
        const val VIEWS = "/view-store/view/create-view"
        const val VIEW_QUERY = "/view-store/view/snapshot/query"
        const val RECORD = """{"kind":"record","columns":["id"]}"""
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}

/**
 * Behind a CoSec gateway (decision D236 ②), as `deploy/view-store` runs it: the gateway authorized each request's
 * `CoSec-App-Id` by the application's roles, and the service, in CoSec's inject mode, trusts the application it
 * forwards (`wow.cosec.trust-injected-app`). Without that switch no application is authenticated at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewStoreCoSecGatewayTest : ViewStoreCoSecContract() {
    override val store: ViewStoreNode = startViewStore(
        "wow.cosec.trust-injected-app" to true,
        profiles = listOf("cosec"),
    )

    @Test
    fun `without trusting the gateway no application is authenticated, and nothing is written or read`() {
        startViewStore(profiles = listOf("cosec")).use { untrusting ->
            val alice = Who(signedIn("alice", USER_ROLE).headers)
            untrusting.post(VIEWS, """{"definitionId":"d","title":"Orders","config":$RECORD}""", alice)
                .assertProblem(BAD_REQUEST, ViewStoreRejections.APP_REQUIRED)
            untrusting.post(VIEW_QUERY, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", alice)
                .assertProblem(BAD_REQUEST, "wow.query.malformed")
        }
    }
}

/**
 * The service authorizing its requests itself (decision D236 ①): CoSec's authorization with the application's role
 * permissions (here in memory, [AppRoles]; CoSec's Redis repositories in a deployment). A request allowed by a
 * permission of the application's roles keeps its views in that application; an application whose roles the user
 * lacks is refused by CoSec.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewStoreCoSecAuthorizationTest : ViewStoreCoSecContract() {
    override val store: ViewStoreNode = startViewStore(
        "cosec.inject.enabled" to false,
        "cosec.authorization.enabled" to true,
        // Without the gateway, the service verifies each token itself, with CoSec's secret.
        "cosec.jwt.enabled" to true,
        "cosec.jwt.secret" to SECRET,
        profiles = listOf("cosec"),
        sources = listOf(AppRoles::class.java),
    )

    @Test
    fun `an application whose roles the user lacks should be refused by CoSec`() {
        create(signedIn("alice", USER_ROLE, app = "other-console"), definition()).status.assert()
            .isEqualTo(FORBIDDEN)
        create(signedIn("alice", "someone-else"), definition()).status.assert().isEqualTo(FORBIDDEN)
    }

    /** CoSec's repositories in memory: no policy, and the role [USER_ROLE] of [APP] may do anything there. */
    @Configuration(proxyBeanMethods = false)
    class AppRoles {
        @Bean
        fun policyRepository(): PolicyRepository = object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(emptyList())

            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.just(emptyList())

            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }

        @Bean
        fun appRolePermissionRepository(): AppRolePermissionRepository = object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<String>,
            ): Mono<CoSecAppRolePermission> {
                if (appId != APP) {
                    return Mono.empty()
                }
                val all = PermissionData(id = "$APP.all", name = "all", action = AllActionMatcher.INSTANCE)
                val permission = AppPermissionData(
                    APP,
                    groups = listOf(PermissionGroupData("all", permissions = listOf(all)))
                )
                val roles = roleIds.filter { it == USER_ROLE || it == ViewStoreRoles.SHARED_WRITER }
                    .map { RolePermissionData(it, setOf(all.id)) }
                return Mono.just(AppRolePermissionData(permission, roles))
            }
        }
    }
}
