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

package me.ahoo.wow.viewstore.server.system

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.RowQueries
import me.ahoo.wow.api.read.dynamic
import me.ahoo.wow.query.read.QueryNotServedException
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.SystemViewSource
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.api.ViewStoreContract
import tools.jackson.databind.node.ObjectNode

/**
 * The system views a request reads: the configured ones of its tenant and application and the stored ones of its
 * application (views of the tenant `(platform)` under the owner `(system)`), which every tenant reads. A stored view
 * wins over a configured one with the same id. The stored ones are read as trusted code, the view store's own, from
 * the tenant `(platform)`. A node whose backend serves no snapshot query serves the configured ones alone.
 */
class StoredSystemViews(
    private val configured: ConfiguredSystemViews,
    private val queries: () -> NodeQueryApi?,
) {
    /** Every system view of [tenantId] and [appId], of [definitionId] when it is given: the configured first. */
    suspend fun systemViews(tenantId: String, appId: String, definitionId: String? = null): List<SystemView> {
        val stored = stored(appId, definitionId, null)
        val storedIds = stored.mapTo(HashSet()) { it.id }
        return configured.systemViews(tenantId, appId)
            .filter { definitionId == null || it.definitionId == definitionId }
            .filterNot { it.id in storedIds } + stored
    }

    /** System view [id] of [tenantId] and [appId]: the stored one, else the configured one; null when neither is. */
    suspend fun systemView(tenantId: String, appId: String, id: String): SystemView? =
        stored(appId, null, id).firstOrNull() ?: configured.systemViews(tenantId, appId).firstOrNull { it.id == id }

    private suspend fun stored(appId: String, definitionId: String?, id: String?): List<SystemView> {
        val views = views() ?: return emptyList()
        val query = listQuery {
            filter {
                tenantId(ViewStoreContext.SYSTEM_TENANT_ID)
                ownerId(ViewStoreContext.SYSTEM_OWNER_ID)
                id?.let { id(it) }
                "state.appId" eq appId
                definitionId?.let { "state.definitionId" eq it }
            }
            sort {
                "firstEventTime".asc()
                "aggregateId".asc()
            }
            limit(MAX_STORED)
        }
        return views.list(query).toList().mapNotNull { node ->
            // One stored view that no longer passes the checks is left out, not every tenant's list.
            runCatching { node.toSystemView() }.onFailure { failure ->
                logger.warn(failure) {
                    "Stored system view [${node["aggregateId"]?.asString()}] of $appId is not a valid system view; " +
                        "it is left out."
                }
            }.getOrNull()
        }
    }

    /** The views' snapshots as stored, or null when there are no queries or no backend serves them. */
    private fun views(): RowQueries<ObjectNode>? = try {
        queries()?.trusted()?.snapshots(VIEW)?.dynamic<ObjectNode>()
    } catch (_: QueryNotServedException) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun ObjectNode.toSystemView(): SystemView {
        val state = this["state"]
        return SystemViews.of(
            id = this["aggregateId"].asString(),
            definitionId = state["definitionId"].asString(),
            title = state["title"].asString(),
            config = WowJson.mapper.convertValue(state["config"], Map::class.java) as Map<String, Any?>,
            source = SystemViewSource.STORED,
            version = this["version"].asInt(),
        )
    }

    companion object {
        /** The most stored system views one application lists, the client's list budget: the oldest are served. */
        const val MAX_STORED: Int = 1000
        private val VIEW = ViewStoreContract.View.TYPE
        private val logger = KotlinLogging.logger {}
    }
}
