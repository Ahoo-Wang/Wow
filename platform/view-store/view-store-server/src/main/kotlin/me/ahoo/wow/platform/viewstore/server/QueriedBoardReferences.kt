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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.platform.viewstore.api.ViewKind
import me.ahoo.wow.platform.viewstore.api.ViewStoreContext
import me.ahoo.wow.platform.viewstore.domain.view.BoardReference
import me.ahoo.wow.platform.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.platform.viewstore.domain.view.ViewConfigs
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.spi.json.WowJson
import tools.jackson.databind.node.ObjectNode

/**
 * The shared dashboards that reference a view, from the view snapshots: the shared dashboards of the tenant and the
 * application (at most [LIMIT]), whose panels are then read here. 9.x matched the panels in the query; the V10 query
 * protocol has no element match into a free-form config, so the config is read instead.
 *
 * A node whose backend serves no snapshot query (the memory backend, by default) knows of no reference, and says so
 * once in its log.
 */
class QueriedBoardReferences(private val queries: () -> HttpQueries) : SharedBoardReferences {
    override suspend fun referencingBoards(tenantId: String, appId: String, viewId: String): List<BoardReference> {
        val gateway = queries().snapshots(VIEW) ?: return none()
        val boards = listQuery {
            filter {
                tenantId(tenantId)
                ownerId(ViewStoreContext.SHARED_OWNER_ID)
                "state.appId" eq appId
                "state.kind" eq ViewKind.DASHBOARD.value
            }
            limit(LIMIT)
        }
        return gateway.list(boards, QueryCaller.IN_PROCESS).toList()
            .filter { viewId in ViewConfigs.referencedViews(it.configOf()) }
            .map { BoardReference(it["aggregateId"].asString(), it["state"]["title"].asString()) }
    }

    private fun none(): List<BoardReference> {
        logger.warn { "No snapshot query serves $VIEW: a claim checks no shared dashboard." }
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ObjectNode.configOf(): Map<String, Any?> =
        WowJson.mapper.convertValue(this["state"]["config"], Map::class.java) as Map<String, Any?>

    companion object {
        /** The most shared dashboards of a tenant and an application a claim reads. */
        const val LIMIT: Int = 1000
        private val VIEW = AggregateType(ViewStoreContext.NAME, ViewStoreContext.VIEW)
        private val logger = KotlinLogging.logger {}
    }
}
