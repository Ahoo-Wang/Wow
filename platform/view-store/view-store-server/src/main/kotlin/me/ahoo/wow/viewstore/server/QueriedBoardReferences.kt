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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.dsl.cursorQuery
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.api.ViewStoreContext
import me.ahoo.wow.viewstore.domain.view.BoardReference
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import tools.jackson.databind.node.ObjectNode

/**
 * The shared dashboards that reference a view, from the view snapshots: every shared dashboard of the tenant and the
 * application, a keyset page of [PAGE] at a time, each read only for its id, its title and its config (the schema
 * projects nothing deeper in it), whose panels' references are then matched here (review A3-18: no dashboard past a
 * bound is missed, and a claim holds one page of configs in memory, never every dashboard's). 9.x matched the panels
 * in the query; the V10 query protocol has no element match into a free-form config, so the panels are read instead.
 *
 * A node whose backend serves no snapshot query (the memory backend, by default) knows of no reference, and says so
 * once in its log.
 */
class QueriedBoardReferences(private val queries: () -> HttpQueries) : SharedBoardReferences {
    override suspend fun referencingBoards(tenantId: String, appId: String, viewId: String): List<BoardReference> {
        val gateway = queries().snapshots(VIEW) ?: return none()
        val references = mutableListOf<BoardReference>()
        var cursor: String? = null
        do {
            val after = cursor
            val page = gateway.cursor(
                cursorQuery {
                    filter {
                        tenantId(tenantId)
                        ownerId(ViewStoreContext.SHARED_OWNER_ID)
                        "state.appId" eq appId
                        "state.kind" eq ViewKind.DASHBOARD.value
                    }
                    projection { include(AGGREGATE_ID, TITLE, PANELS) }
                    size(PAGE)
                    cursor(after)
                },
                QueryCaller.IN_PROCESS,
            )
            page.list.filter { viewId in ViewConfigs.referencedViews(it.configOf()) }
                .mapTo(references) { BoardReference(it[AGGREGATE_ID].asString(), it["state"]["title"].asString()) }
            cursor = page.nextCursor
        } while (cursor != null)
        return references
    }

    private fun none(): List<BoardReference> {
        logger.warn { "No snapshot query serves $VIEW: a claim checks no shared dashboard." }
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ObjectNode.configOf(): Map<String, Any?> =
        this["state"]?.get("config")?.let { WowJson.mapper.convertValue(it, Map::class.java) as Map<String, Any?> }
            ?: emptyMap()

    companion object {
        /** The shared dashboards a claim reads at a time. */
        const val PAGE: Int = 50
        private const val AGGREGATE_ID = "aggregateId"
        private const val TITLE = "state.title"
        private const val PANELS = "state.config"
        private val VIEW = AggregateType(ViewStoreContext.NAME, ViewStoreContext.VIEW)
        private val logger = KotlinLogging.logger {}
    }
}
