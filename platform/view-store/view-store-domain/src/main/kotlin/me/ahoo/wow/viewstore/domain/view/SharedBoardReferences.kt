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

/**
 * Answers which shared dashboards reference a view: the view store's own snapshot query, kept behind this port so the
 * domain does not depend on the query stack. The service binds it (decision D107).
 */
fun interface SharedBoardReferences {
    /**
     * The shared dashboards (owner `(shared)`) of [tenantId] and [appId], not deleted, whose panels reference [viewId]
     * at any of [ViewConfigs.PANEL_REFERENCES].
     */
    suspend fun referencingBoards(tenantId: String, appId: String, viewId: String): List<BoardReference>
}

/** A shared dashboard: its id and its title. */
data class BoardReference(val id: String, val title: String)
