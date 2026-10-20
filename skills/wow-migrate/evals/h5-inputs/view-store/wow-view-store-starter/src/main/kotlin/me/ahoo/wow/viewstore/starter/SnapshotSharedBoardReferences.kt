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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.query.snapshot.toState
import me.ahoo.wow.viewstore.ViewStoreService.SHARED_OWNER_ID
import me.ahoo.wow.viewstore.api.ViewKind
import me.ahoo.wow.viewstore.domain.view.BoardReference
import me.ahoo.wow.viewstore.domain.view.SharedBoardReferences
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import me.ahoo.wow.viewstore.domain.view.ViewState
import reactor.core.publisher.Flux

/**
 * The shared dashboards referencing a view, from the view snapshots: same tenant, same application, owner
 * `(shared)`, not deleted (the snapshot model's default), kind dashboard, and the view among their panels'
 * references.
 */
internal class SnapshotSharedBoardReferences(
    private val snapshotQueryGateway: () -> SnapshotQueryGateway<ViewState>,
) : SharedBoardReferences {
    companion object {
        const val MAX_BOARDS = 20
        private const val CONFIG = "config"
    }

    override fun referencingBoards(tenantId: String, appId: String, viewId: String): Flux<BoardReference> {
        return listQuery {
            limit(MAX_BOARDS)
            filter {
                tenantId(tenantId)
                ownerId(SHARED_OWNER_ID)
                pathState {
                    ViewStoreScopeContributor.APP_ID_FIELD eq appId
                    "$CONFIG.${ViewConfigs.KIND}" eq ViewKind.DASHBOARD.value
                    or {
                        ViewConfigs.PANEL_REFERENCES.forEach { reference ->
                            "$CONFIG.${ViewConfigs.PANELS}".elementMatch { reference eq viewId }
                        }
                    }
                }
            }
        }.query(snapshotQueryGateway()).toState().map { BoardReference(it.id, it.title) }
    }
}
