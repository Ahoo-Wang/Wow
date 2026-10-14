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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext
import me.ahoo.wow.runtime.node.ServiceNode
import org.junit.jupiter.api.Test

/** The view store service as its `application.yaml` configures it, on random ports. */
class ViewStoreServerTest {
    @Test
    fun `the server should run one node of the view store context on the memory backend`() {
        startViewStore().use { store ->
            val node = store.context.getBean(ServiceNode::class.java)
            node.service.assert().isEqualTo("view-store-service")
            node.isReady.assert().isTrue()
            node.contexts.keys.assert().containsExactly(ViewStoreContext.NAME)
            val manifest = store.context.getBean(Manifest::class.java)
            manifest.contexts.single().aggregates.map { it.name }.assert().containsExactlyInAnyOrder(
                ViewStoreContext.VIEW,
                ViewStoreContext.VIEW_PREFERENCES,
                ViewStoreContext.DATASET_WORDING,
            )
            // The memory backend serves no snapshot query: a claim then knows of no shared dashboard.
            store.post("/view-store/view/snapshot/query", """{"kind":"list","filter":{"op":"MATCH_ALL"}}""", Who.user("a"))
                .status.assert().isEqualTo(NOT_FOUND)
            val references = QueriedBoardReferences { store.context.getBean(HttpQueries::class.java) }
            runBlocking { references.referencingBoards("(0)", "app", "view-1") }.assert().isEmpty()
        }
    }

    private companion object {
        const val NOT_FOUND = 404
    }
}
