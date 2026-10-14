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

package me.ahoo.wow.platform.viewstore.server

import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.WowHeaders
import me.ahoo.wow.platform.viewstore.domain.ViewStoreRejections
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

/**
 * The view store end to end over HTTP (roadmap 7.2a): views created, updated, shared, claimed and queried per owner
 * and tenant (decision D189), preferences and wording by their derived ids, and the application kept apart. Each test
 * works on a definition of its own, so the tests share the server without seeing each other's views. It runs on the
 * memory backend ([ViewStoreMemoryTest]) and on PostgreSQL (`ViewStorePostgresTest`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ViewStoreContract {
    /** The view store server the requests go to. */
    protected abstract val store: ViewStoreNode

    @AfterAll
    fun closeStore() {
        store.close()
        afterClose()
    }

    /** What else the test stops once the store is closed, such as its database. */
    protected open fun afterClose() = Unit

    private fun definition(): String = "orders-${UUID.randomUUID()}"

    private fun create(who: Who, definition: String, title: String = "Orders", config: String = RECORD): String =
        store.post(VIEWS, """{"definitionId":"$definition","title":"$title","config":$config}""", who).assertOk().id

    /** The views of [definition] [who] lists, by id. */
    private fun list(who: Who, definition: String): List<String> = store.post(
        VIEW_QUERY,
        """{"kind":"list","filter":{"op":"EQ","field":"state.definitionId","value":"$definition"},
            "sort":[{"field":"firstEventTime","direction":"ASC"}]}""",
        who,
    ).assertOk().ids

    @Test
    fun `a user should create, save, rename and delete a personal view only it sees`() {
        val definition = definition()
        val id = create(ALICE, definition, title = "  Open orders ")
        store.post("/view-store/view/$id/save-view", """{"config":$DASHBOARD}""", ALICE).assertOk()
            .json["version"].asInt().assert().isEqualTo(2)
        store.post("/view-store/view/$id/rename-view", """{"title":"Closed orders"}""", ALICE, ifMatch(2))
            .assertOk().json["version"].asInt().assert().isEqualTo(3)
        // A stale version is refused with If-Match as 412.
        store.post("/view-store/view/$id/rename-view", """{"title":"Late"}""", ALICE, ifMatch(2))
            .assertProblem(PRECONDITION_FAILED, "wow.aggregate.version-mismatch")
        val single = store.post(VIEW_QUERY, """{"kind":"single","filter":{"op":"ID","value":"$id"}}""", ALICE)
            .assertOk().json
        single["ownerId"].asString().assert().isEqualTo("alice")
        single["state"].let { state ->
            state["title"].asString().assert().isEqualTo("Closed orders")
            state["audience"].asString().assert().isEqualTo("personal")
            state["kind"].asString().assert().isEqualTo("dashboard")
            state["config"]["panels"][0]["instanceId"].asString().assert().isEqualTo("v1")
        }
        list(ALICE, definition).assert().containsExactly(id)
        list(BOB, definition).assert().isEmpty()
        store.post("/view-store/view/$id/rename-view", """{"title":"Mine"}""", BOB)
            .assertProblem(FORBIDDEN, "wow.aggregate.forbidden")
        store.delete("/view-store/view/$id", ALICE).assertOk()
        list(ALICE, definition).assert().isEmpty()
    }

    @Test
    fun `a view should be kept in its tenant`() {
        val definition = definition()
        val id = create(ALICE, definition)
        list(ALICE_ELSEWHERE, definition).assert().isEmpty()
        create(ALICE_ELSEWHERE, definition).assert().isNotEqualTo(id)
        list(ALICE, definition).assert().containsExactly(id)
    }

    @Test
    fun `sharing should move a view to (shared), and claiming it to the user who claims it`() {
        val definition = definition()
        val id = create(ALICE, definition)
        store.post("/view-store/view/$id/share-view", "{}", ALICE, MOVED).assertOk()
        list(ALICE, definition).assert().isEmpty()
        list(BOB.shared, definition).assert().containsExactly(id)
        // Shared again: nothing changes. Version 2 shared it, version 3 is the saga's move to (shared).
        store.post("/view-store/view/$id/share-view", "{}", CAROL.shared).assertOk()
            .json["version"].asInt().assert().isEqualTo(3)
        // Writing a shared view takes the shared role.
        store.post("/view-store/view/$id/rename-view", """{"title":"Team"}""", BOB.shared)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        store.post("/view-store/view/$id/claim-view", "{}", BOB.shared).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        store.post("/view-store/view/$id/claim-view", "{}", CAROL.shared, MOVED).assertOk()
        list(CAROL, definition).assert().containsExactly(id)
        list(BOB.shared, definition).assert().isEmpty()
        val view = store.post(VIEW_QUERY, """{"kind":"single","filter":{"op":"ID","value":"$id"}}""", CAROL).json
        view["ownerId"].asString().assert().isEqualTo("carol")
        view["state"]["audience"].asString().assert().isEqualTo("personal")
    }

    @Test
    fun `a shared view a shared dashboard shows should stay shared`() {
        val definition = definition()
        val view = create(CAROL.shared, definition)
        create(CAROL.shared, definition, title = "Weekly board", config = dashboardOf(view))
        store.post("/view-store/view/$view/claim-view", "{}", CAROL.shared)
            .assertProblem(CONFLICT, ViewStoreRejections.REFERENCED_BY_SHARED_DASHBOARD)
            .json["detail"].asString().assert().contains("Weekly board")
    }

    @Test
    fun `a stored system view should be written by a system writer and read by everyone`() {
        val definition = definition()
        val id = create(ADMIN.system, definition)
        list(ALICE.system, definition).assert().containsExactly(id)
        list(ALICE_ELSEWHERE.system, definition).assert().containsExactly(id)
        store.post(VIEWS, """{"definitionId":"$definition","title":"Mine","config":$RECORD}""", ALICE.system)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        store.post("/view-store/view/$id/share-view", "{}", ADMIN.system)
            .assertProblem(FORBIDDEN, ViewStoreRejections.SYSTEM_VIEW_READ_ONLY)
    }

    @Test
    fun `a view's owner should change only by sharing or claiming`() {
        val id = create(ALICE, definition())
        store.post("/view-store/view/$id/wow.lifecycle.transfer-owner", """{"ownerId":"bob"}""", ALICE)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        // Nobody signed in reads the shared views, and writes none.
        store.post(VIEWS, """{"definitionId":"d","title":"Anyone's","config":$RECORD}""", Who.ANONYMOUS.shared)
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
            .json["detail"].asString().assert().startsWith("An anonymous caller")
        // A view's events carry no application to keep a query in.
        store.post(VIEW_EVENTS, """{"kind":"list","filter":{"op":"ID","value":"$id"}}""", ALICE.withoutApp)
            .assertOk().json.values().map { it["version"].asInt() }.assert().containsExactly(1)
        // Acting as another user is refused: only the reserved owners are open to Wow-Scope.
        store.post(
            VIEW_QUERY,
            """{"kind":"list","filter":{"op":"MATCH_ALL"}}""",
            ALICE.with(WowHeaders.SCOPE, "owner=bob")
        )
            .assertProblem(FORBIDDEN, "wow.auth.forbidden")
        store.post(
            VIEW_QUERY,
            """{"kind":"list","filter":{"op":"MATCH_ALL"}}""",
            ALICE.with(WowHeaders.SCOPE, "owner=")
        )
            .assertProblem(BAD_REQUEST, "wow.query.malformed")
    }

    @Test
    fun `a view should be kept in its application`() {
        val definition = definition()
        val id = create(ALICE, definition)
        list(ALICE.inApp("other-console"), definition).assert().isEmpty()
        store.post("/view-store/view/$id/rename-view", """{"title":"Elsewhere"}""", ALICE.inApp("other-console"))
            .assertProblem(NOT_FOUND, ViewStoreRejections.NOT_FOUND)
        store.post(VIEWS, """{"definitionId":"$definition","title":"No app","config":$RECORD}""", ALICE.withoutApp)
            .assertProblem(BAD_REQUEST, ViewStoreRejections.APP_REQUIRED)
        store.post(VIEW_QUERY, """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", ALICE.withoutApp)
            .assertProblem(BAD_REQUEST, "wow.query.malformed")
        store.post(VIEWS, """{"definitionId":"$definition","title":"Open","config":{"kind":"chart"}}""", ALICE)
            .assertProblem(BAD_REQUEST, ViewStoreRejections.INVALID)
        store.post(VIEWS, """{"definitionId":"$definition","title":"Nobody's","config":$RECORD}""", Who.ANONYMOUS)
            .assertProblem(BAD_REQUEST, ViewStoreRejections.OWNER_REQUIRED)
    }

    @Test
    fun `preferences should be written by their derived id, once as never written`() {
        val definition = definition()
        val body = """{"definitionId":"$definition","order":["v2","v1"],"defaultInstanceId":"v2","autoRun":true}"""
        val first = store.post(PREFERENCES, body, ALICE, ifMatch(0)).assertOk()
        first.json["version"].asInt().assert().isEqualTo(1)
        store.post(PREFERENCES, body.replace("v2\",\"v1", "v1"), ALICE, ifMatch(0))
            .assertProblem(PRECONDITION_FAILED, "wow.aggregate.version-mismatch")
        // A request naming another id is sent to the derived one all the same.
        store.post("/view-store/view_preferences/someone-else/set-view-preferences", body, ALICE).assertOk()
            .id.assert().isEqualTo(first.id)
        val bobs = store.post(PREFERENCES, """{"definitionId":"$definition"}""", BOB, ifMatch(0)).assertOk()
        bobs.id.assert().isNotEqualTo(first.id)
        val query = """{"kind":"single","filter":{"op":"EQ","field":"state.definitionId","value":"$definition"}}"""
        store.post(PREFERENCES_QUERY, query, ALICE).assertOk().json["state"]["order"].values().map { it.asString() }
            .assert().containsExactly("v2", "v1")
        store.post(PREFERENCES_QUERY, query, BOB).assertOk().json["state"]["order"].isEmpty.assert().isTrue()
        store.post(PREFERENCES, """{"definitionId":"$definition"}""", ALICE.withoutApp)
            .assertProblem(BAD_REQUEST, ViewStoreRejections.APP_REQUIRED)
    }

    @Test
    fun `a tenant's wording should be written by the shared role and read by everyone of the tenant`() {
        val dataset = "sales-${UUID.randomUUID()}"
        val body = """{"dataset":"$dataset","title":"Sales","recordNoun":"order","fields":{"amount":{"label":"金额"}}}"""
        store.post(WORDING, body, ALICE).assertProblem(FORBIDDEN, "wow.auth.forbidden")
        val id = store.post(WORDING, body, CAROL, ifMatch(0)).assertOk().id
        val query = """{"kind":"single","filter":{"op":"ID","value":"$id"}}"""
        store.post(WORDING_QUERY, query, BOB).assertOk().json["state"].let { state ->
            state["title"].asString().assert().isEqualTo("Sales")
            state["fields"]["amount"]["label"].asString().assert().isEqualTo("金额")
        }
        store.post(WORDING_QUERY, query, ALICE_ELSEWHERE).assertProblem(NOT_FOUND, "wow.aggregate.not-found")
        store.post(WORDING_CLEAR, """{"dataset":"$dataset"}""", CAROL).assertOk()
        store.post(WORDING_QUERY, query, BOB).assertOk().json["state"]["title"].isNull.assert().isTrue()
        store.post(WORDING_CLEAR, """{"dataset":"$dataset"}""", CAROL.withoutApp)
            .assertProblem(NOT_FOUND, "wow.aggregate.not-found")
    }

    @Test
    fun `the OpenAPI document should list the view store's routes`() {
        val paths = store.get("/wow/openapi", ALICE).assertOk().json["paths"]
        listOf(VIEWS, "/view-store/view/{id}/claim-view", PREFERENCES, WORDING_CLEAR, VIEW_QUERY).forEach {
            paths.has(it).assert().describedAs(it).isTrue()
        }
    }

    private fun ifMatch(version: Int): Map<String, String> = mapOf(WowHeaders.IF_MATCH to "\"$version\"")

    protected companion object {
        const val VIEWS = "/view-store/view/create-view"
        const val VIEW_QUERY = "/view-store/view/snapshot/query"
        const val VIEW_EVENTS = "/view-store/view/events/query"
        const val PREFERENCES = "/view-store/view_preferences/set-view-preferences"
        const val PREFERENCES_QUERY = "/view-store/view_preferences/snapshot/query"
        const val WORDING = "/view-store/dataset_wording/set-dataset-wording"
        const val WORDING_CLEAR = "/view-store/dataset_wording/clear-dataset-wording"
        const val WORDING_QUERY = "/view-store/dataset_wording/snapshot/query"
        const val RECORD = """{"kind":"record","columns":["id"]}"""
        const val DASHBOARD = """{"kind":"dashboard","panels":[{"instanceId":"v1"}]}"""

        fun dashboardOf(view: String): String = """{"kind":"dashboard","panels":[{"click":{"instanceId":"$view"}}]}"""

        /** The wait of a share or a claim, until the saga moved the owner and the snapshot holds it. */
        val MOVED: Map<String, String> = mapOf(
            WowHeaders.WAIT to "handled(ViewAudienceSaga.onAudienceChanged)>snapshot"
        )

        val ALICE = Who.user("alice")
        val BOB = Who.user("bob")
        val CAROL = Who.user("carol", null, ViewStoreCallers.SHARED_WRITER)
        val ADMIN = Who.user("admin", null, ViewStoreCallers.SYSTEM_WRITER)
        val ALICE_ELSEWHERE = Who.user("alice", "tenant-2")
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val PRECONDITION_FAILED = 412
    }
}
