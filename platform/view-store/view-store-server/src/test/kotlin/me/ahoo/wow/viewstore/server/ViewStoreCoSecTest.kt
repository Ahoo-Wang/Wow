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

import me.ahoo.cosec.runtime.identity.IdentityCodec
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.wire.WowHeaders
import me.ahoo.wow.test.server.Answer
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The view store in its production shape, the `cosec` profile (roadmap P.15), end to end over HTTP, as
 * `deploy/view-store` runs it behind the CoSec gateway: the gateway verified each request's credential and decided it
 * in an app, and forwards what it verified in `CoSec-Identity`, which CoSec's gateway mode reads. The user is its
 * principal, and every view is kept in the app of its scope (decisions D232, D236), never in the `CoSec-App-Id` a
 * client merely sends.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ViewStoreCoSecTest {
    private val store: ViewStoreNode = startViewStore(profiles = listOf("cosec"))

    @AfterAll
    fun closeStore() {
        store.close()
    }

    /**
     * [id] of CoSec's default tenant with [roles], decided by the gateway in [app], as the gateway forwards it: the
     * service holds no secret and verifies nothing, it believes the header.
     */
    private fun signedIn(id: String, vararg roles: String, app: String = APP): Who {
        val now = Instant.now()
        val identity = Identity(
            tenant = "(0)",
            subject = id,
            app = app,
            space = "",
            roles = roles.toSet(),
            credential = Identity.Credential(Identity.Method.JWT, "token-of-$id", now + Duration.ofHours(1)),
            issuedAt = now,
            requestId = "request-of-$id",
        )
        return Who(mapOf(IdentityCodec.HEADER to IdentityCodec.encode(identity)))
    }

    private fun definition(): String = "orders-${UUID.randomUUID()}"

    private fun create(who: Who, definition: String): Answer =
        store.post(VIEWS, """{"definitionId":"$definition","title":"Orders","config":$RECORD}""", who)

    private fun list(who: Who, definition: String): Answer = store.post(
        VIEW_QUERY,
        """{"kind":"list","filter":{"op":"eq","field":"state.definitionId","value":"$definition"}}""",
        who,
    )

    @Test
    fun `a signed-in user should keep views in the app the gateway decided in, and read only its own`() {
        val definition = definition()
        // The app a client declares is not the one the gateway decided the request in.
        val alice = signedIn("alice", USER_ROLE).with(ViewStoreContext.APP_ID_HEADER, "other-console")
        val id = create(alice, definition).assertOk().id
        val view = store.post(VIEW_QUERY, """{"kind":"single","filter":{"op":"id","value":"$id"}}""", alice)
            .assertOk().json
        view["state"]["appId"].asString().assert().isEqualTo(APP)
        view["ownerId"].asString().assert().isEqualTo("alice")
        list(alice, definition).assertOk().ids.assert().containsExactly(id)
        list(signedIn("bob", USER_ROLE), definition).assertOk().ids.assert().isEmpty()
        list(signedIn("alice", USER_ROLE, app = "other-console"), definition).assertOk().ids.assert().isEmpty()
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
    fun `a signed-in user should read the view store's own routes in the app the gateway decided in`() {
        val alice = signedIn("alice", USER_ROLE)
        val id = create(alice, definition()).assertOk().id
        store.get("$VIEW_BY_ID/$id", alice).assertOk().json["scope"].asString().assert().isEqualTo("personal")
        store.get(SYSTEM_VIEWS, alice).assertOk()
    }

    @Test
    fun `an identity without an app should write and read nothing, whatever app the client declares`() {
        val alice = signedIn("alice", USER_ROLE, app = "").with(ViewStoreContext.APP_ID_HEADER, APP)
        create(alice, definition()).assertProblem(BAD_REQUEST, ViewStoreRejections.APP_REQUIRED)
        store.post(VIEW_QUERY, """{"kind":"list","filter":{"op":"match-all"}}""", alice)
            .assertProblem(BAD_REQUEST, "wow.query.malformed")
        store.get(SYSTEM_VIEWS, alice).assertProblem(BAD_REQUEST, ViewStoreRejections.APP_REQUIRED)
    }

    @Test
    fun `a request without an identity should be answered 401`() {
        val app = Who(mapOf(ViewStoreContext.APP_ID_HEADER to APP))
        create(app, definition()).status.assert().isEqualTo(UNAUTHORIZED)
        list(app, definition()).status.assert().isEqualTo(UNAUTHORIZED)
        store.get(SYSTEM_VIEWS, app).status.assert().isEqualTo(UNAUTHORIZED)
    }

    private companion object {
        const val APP = "orders-console"
        const val USER_ROLE = "orders-user"
        const val VIEWS = "/view-store/view/create-view"
        const val VIEW_QUERY = "/view-store/view/snapshot/query"
        const val SYSTEM_VIEWS = "/view-store/system-views"
        const val VIEW_BY_ID = "/view-store/views"
        const val RECORD = """{"kind":"record","columns":["id"]}"""
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
    }
}
