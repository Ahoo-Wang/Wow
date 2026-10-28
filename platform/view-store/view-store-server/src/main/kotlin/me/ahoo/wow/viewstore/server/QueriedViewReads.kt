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

import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.QueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.query.read.QueryNotServedException
import me.ahoo.wow.query.read.trees
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.ViewAudience
import me.ahoo.wow.viewstore.api.ViewEntry
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreContract
import me.ahoo.wow.viewstore.domain.view.ViewReads
import me.ahoo.wow.viewstore.server.system.StoredSystemViews
import tools.jackson.databind.node.ObjectNode

/**
 * The view reads of the view store's routes: the system views from [systemViews], the saved views from the view
 * snapshots. The snapshots are read as trusted code, the view store's own, which no query policy restricts, so each
 * read keeps to its application here as [ViewStoreQueryPolicy] does for a caller's query. A node whose backend serves
 * no snapshot query reads no saved view.
 */
class QueriedViewReads(
    private val systemViews: StoredSystemViews,
    private val queries: () -> QueryApi?,
) : ViewReads {
    override suspend fun systemViews(tenantId: String, appId: String, definitionId: String?): List<SystemView> =
        systemViews.systemViews(tenantId, appId, definitionId)

    override suspend fun systemView(tenantId: String, appId: String, id: String): SystemView? =
        systemViews.systemView(tenantId, appId, id)

    override suspend fun savedViews(tenantId: String, appId: String, id: String, owners: Set<String>): List<ViewEntry> {
        val views = views() ?: return emptyList()
        val query = listQuery {
            filter {
                tenantId(tenantId)
                id(id)
                or { owners.forEach { ownerId(it) } }
                ViewStoreQueryPolicy.APP_ID eq appId
            }
            limit(owners.size)
        }
        return views.list(query).toList().map { it.toEntry() }
    }

    /** The views' snapshots as stored, or null when there are no queries or no backend serves them. */
    private fun views(): RowQueries<ObjectNode>? = try {
        queries()?.trusted()?.snapshots(VIEW)?.trees()
    } catch (_: QueryNotServedException) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun ObjectNode.toEntry(): ViewEntry {
        val state = this["state"]
        val version = this["version"].asInt()
        return ViewEntry(
            id = this["aggregateId"].asString(),
            definitionId = state["definitionId"].asString(),
            title = state["title"].asString(),
            kind = checkNotNull(ViewKind.of(state["kind"].asString())) { "A saved view has a kind." },
            scope = ViewAudience.ofOwner(this["ownerId"].asString()),
            revision = version.toString(),
            config = WowJson.mapper.convertValue(state["config"], Map::class.java) as Map<String, Any?>,
            version = version,
        )
    }

    private companion object {
        val VIEW = ViewStoreContract.View.TYPE
    }
}
