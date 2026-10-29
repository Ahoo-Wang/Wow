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

package me.ahoo.wow.viewstore.domain.view

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient
import me.ahoo.wow.api.read.Queries
import me.ahoo.wow.api.route.RouteCall
import me.ahoo.wow.api.route.RouteRefusal
import me.ahoo.wow.kernel.Dependencies
import me.ahoo.wow.kernel.ModelProvider
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.SystemViewSource
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewEntry
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.domain.ViewStoreRejections
import org.junit.jupiter.api.Test

/** The view store's own reads: in the caller's application, a view by id read personal first, then shared, then system. */
class ViewStoreRoutesTest {
    private class Call(owner: String?, app: String? = APP) : RouteCall {
        override val caller: Caller = Caller.of(
            tenantId = TENANT,
            scope = Scope(ownerId = owner),
            attributes = app?.let { mapOf(Caller.APP to listOf(it)) }.orEmpty(),
        )
        override val tenantId: String = TENANT
        override val scope: Scope = caller.scope
        override val requestId: String = "r1"
        override val commands: CommandClient get() = error("The view store's reads send no command.")
        override val queries: Queries get() = error("The view store's reads run no query of the node.")
    }

    /** Reads over [saved], by owner, and [system]: what each read was asked is kept in [asked]. */
    private class Reads(
        private val saved: Map<String, ViewEntry> = emptyMap(),
        private val system: List<SystemView> = emptyList(),
    ) : ViewReads {
        val asked = mutableListOf<String>()

        override suspend fun systemViews(tenantId: String, appId: String, definitionId: String?): List<SystemView> {
            asked += "systemViews($tenantId, $appId, $definitionId)"
            return system.filter { definitionId == null || it.definitionId == definitionId }
        }

        override suspend fun systemView(tenantId: String, appId: String, id: String): SystemView? =
            system.firstOrNull { it.id == id }

        override suspend fun savedViews(
            tenantId: String,
            appId: String,
            id: String,
            owners: Set<String>,
        ): List<ViewEntry> {
            asked += "savedViews($tenantId, $appId, $id, $owners)"
            return saved.filterKeys { it in owners }.values.filter { it.id == id }
        }
    }

    private fun saved(scope: ViewAudience, title: String) = ViewEntry(
        id = ID,
        definitionId = "orders",
        title = title,
        kind = ViewKind.RECORD,
        scope = scope,
        revision = "1",
        config = mapOf("kind" to "record"),
        version = 1,
    )

    private val system = SystemView(
        id = ID,
        definitionId = "orders",
        title = "System",
        kind = ViewKind.ANALYSIS,
        revision = "0123456789abcdef",
        config = mapOf("kind" to "analysis"),
        source = SystemViewSource.STORED,
        version = 3,
    )

    private val personal = saved(ViewAudience.PERSONAL, "Personal")
    private val shared = saved(ViewAudience.SHARED, "Shared")

    @Test
    fun `a view should be read personal first, then shared, then system`() = runBlocking<Unit> {
        val all = ViewStoreRoutes(
            Reads(mapOf("alice" to personal, ViewStoreContext.SHARED_OWNER_ID to shared), listOf(system)),
        )
        all.view(Call("alice"), ID).assert().isEqualTo(personal)
        all.view(Call("bob"), ID).assert().isEqualTo(shared)
        ViewStoreRoutes(Reads(system = listOf(system))).view(Call("alice"), ID).assert().isEqualTo(
            ViewEntry(
                id = ID,
                definitionId = "orders",
                title = "System",
                kind = ViewKind.ANALYSIS,
                scope = ViewAudience.SYSTEM,
                revision = "0123456789abcdef",
                config = mapOf("kind" to "analysis"),
                version = 3,
                source = SystemViewSource.STORED,
            ),
        )
    }

    @Test
    fun `a caller without an owner or acting as a reserved one should read no personal view`() = runBlocking<Unit> {
        val reads = Reads(mapOf(ViewStoreContext.SHARED_OWNER_ID to shared))
        val routes = ViewStoreRoutes(reads)
        routes.view(Call(null), ID).assert().isEqualTo(shared)
        routes.view(Call(ViewStoreContext.SHARED_OWNER_ID), ID).assert().isEqualTo(shared)
        reads.asked.assert().containsExactly(
            "savedViews($TENANT, $APP, $ID, [(shared)])",
            "savedViews($TENANT, $APP, $ID, [(shared)])",
        )
    }

    @Test
    fun `no view, or no application, should be refused with its code`() {
        val routes = ViewStoreRoutes(Reads())
        refusal { routes.view(Call("alice"), ID) }.assert().isEqualTo(ViewStoreRejections.NOT_FOUND)
        refusal { routes.view(Call("alice", app = null), ID) }.assert().isEqualTo(ViewStoreRejections.APP_REQUIRED)
        refusal { routes.systemViews(Call("alice", app = null), null) }.assert()
            .isEqualTo(ViewStoreRejections.APP_REQUIRED)
    }

    /** The code of the refusal [read] throws. */
    private fun refusal(read: suspend () -> Any?): String? =
        (runCatching { runBlocking { read() } }.exceptionOrNull() as? RouteRefusal)?.code

    @Test
    fun `the system views should be every definition's when the definition is absent or blank`() = runBlocking<Unit> {
        val reads = Reads(system = listOf(system))
        val routes = ViewStoreRoutes(reads)
        routes.systemViews(Call("alice"), null).assert().containsExactly(system)
        routes.systemViews(Call("alice"), " ").assert().containsExactly(system)
        routes.systemViews(Call("alice"), "other").assert().isEmpty()
        reads.asked.assert().containsExactly(
            "systemViews($TENANT, $APP, null)",
            "systemViews($TENANT, $APP, null)",
            "systemViews($TENANT, $APP, other)",
        )
    }

    @Test
    fun `the model provider should bind both routes to the host's instance`() = runBlocking<Unit> {
        val provider = ModelProvider.load().single()
        val routes = provider.routes(Dependencies.of(ViewStoreRoutes(Reads(system = listOf(system)))))
        routes.map { "${it.context}.${it.name}" }.assert()
            .containsExactlyInAnyOrder("view-store.systemViews", "view-store.view")
    }

    private companion object {
        const val TENANT = "t1"
        const val APP = "orders-console"
        const val ID = "v1"
    }
}
