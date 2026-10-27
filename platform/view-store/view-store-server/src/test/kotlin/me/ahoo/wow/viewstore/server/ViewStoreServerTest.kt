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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.node.ServiceNode
import me.ahoo.wow.viewstore.api.ViewStoreContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.io.File

/** The view store service as its `application.yaml` configures it, on random ports. */
class ViewStoreServerTest {
    @Test
    fun `the server should run one node of the view store context on the memory backend`() {
        startViewStore("wow.query.memory.enabled" to false).use { store ->
            val node = store.context.getBean(ServiceNode::class.java)
            node.service.assert().isEqualTo("view-store-service")
            node.isReady.assert().isTrue()
            node.hostedContexts.assert().containsExactly(ViewStoreContext.NAME)
            val manifest = store.context.getBean(Manifest::class.java)
            manifest.contexts.single().aggregates.map { it.name }.assert().containsExactlyInAnyOrder(
                ViewStoreContext.VIEW,
                ViewStoreContext.VIEW_PREFERENCES,
                ViewStoreContext.DATASET_WORDING,
            )
            // Without the in-memory query backend, no snapshot query is served: a claim then knows of no shared
            // dashboard.
            store.post(
                "/view-store/view/snapshot/query",
                """{"kind":"list","filter":{"op":"MATCH_ALL"}}""",
                Who.user("a")
            )
                .status.assert().isEqualTo(NOT_FOUND)
            val references = QueriedBoardReferences { store.context.getBean(QueryApi::class.java) }
            runBlocking { references.referencingBoards("(0)", "app", "view-1") }.assert().isEmpty()
            // Without snapshot queries, the configured system views alone.
            store.get("/view-store/system-views", Who.user("a")).assertOk().json.values().map { it["id"].asString() }
                .assert().isEmpty()
        }
    }

    @Test
    fun `in development the callers are the host's, behind the reserved owners`() {
        startViewStore().use { store ->
            store.context.getBean(me.ahoo.wow.http.caller.CallerResolver::class.java).assert()
                .isInstanceOf(ReservedScopeCallers::class.java)
            store.get("/view-store/view/v-1/state", Who.ANONYMOUS).status.assert().isEqualTo(NOT_FOUND)
        }
    }

    @Test
    fun `the Manifest baseline should list every command route the server serves`() {
        startViewStore().use { store ->
            val served = commandRoutes(store.get("/wow/manifest", Who.user("a")).assertOk().json)
            served.assert().contains("POST /view-store/dataset_wording/clear-dataset-wording")
            commandRoutes(json.readTree(File("manifest/baseline.json"))).assert().isEqualTo(served)
        }
    }

    /** The command routes of [manifest], a Manifest tree, as `METHOD path`. */
    private fun commandRoutes(manifest: JsonNode): Set<String> = manifest["contexts"].values().flatMap { context ->
        context["aggregates"].values().flatMap { aggregate ->
            aggregate["commands"].values().flatMap { command ->
                command["routes"].values().map { "${it["method"].asString()} ${it["path"].asString()}" }
            }
        }
    }.toSet()

    @Test
    fun `the configured system views should be served without snapshot queries`() {
        startViewStore(
            "wow.view-store.system-views[0].id" to "all",
            "wow.view-store.system-views[0].definition-id" to "orders",
            "wow.view-store.system-views[0].title" to "All",
            "wow.view-store.system-views[0].config" to """{"kind":"analysis"}""",
        ).use { store ->
            store.get("/view-store/system-views", Who.user("a")).assertOk().json.values().map { it["id"].asString() }
                .assert().containsExactly("all")
            // A blank definition is none.
            store.get("/view-store/system-views?definitionId=", Who.user("a")).assertOk().json.size().assert()
                .isEqualTo(1)
            store.get("/view-store/system-views/all", Who.user("a")).assertOk().json["kind"].asString().assert()
                .isEqualTo("analysis")
        }
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}
