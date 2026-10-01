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

package me.ahoo.wow.viewstore.starter.system

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.query.dsl.listQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.viewstore.ViewStoreService.SYSTEM_OWNER_ID
import me.ahoo.wow.viewstore.ViewStoreService.SYSTEM_TENANT_ID
import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.api.SystemViewSource
import me.ahoo.wow.viewstore.domain.view.ViewState
import me.ahoo.wow.viewstore.starter.ViewStoreQueryPolicy
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * The system views a request reads: the configured ones of its tenant and application ([SystemViewProvider]) and the
 * stored ones of its application ([StoredSystemViewSource]), which every tenant reads. A stored view wins over a
 * configured one with the same id (a warning is logged once per id).
 */
internal class StoredSystemViews(
    private val configured: SystemViewProvider,
    private val stored: StoredSystemViewSource,
) {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val warnedClashes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Every system view of [tenantId] and [appId], of [definitionId] when one is given: the configured first. */
    fun systemViews(tenantId: String, appId: String, definitionId: String? = null): Flux<SystemView> =
        stored.storedSystemViews(appId, definitionId, null)
            .collectList()
            .flatMapMany { storedViews ->
                val storedIds = storedViews.mapTo(HashSet()) { it.id }
                configured.systemViews(tenantId, appId)
                    .filter { definitionId == null || it.definitionId == definitionId }
                    .filter { !storedIds.contains(it.id).also { clash -> if (clash) warnClash(it.id) } }
                    .concatWith(Flux.fromIterable(storedViews))
            }

    /** System view [id] of [tenantId] and [appId]: the stored one, else the configured one. */
    fun systemView(tenantId: String, appId: String, id: String): Mono<SystemView> =
        stored.storedSystemViews(appId, null, id).next()
            .flatMap { storedView ->
                configured.systemViews(tenantId, appId).any { it.id == id }
                    .doOnNext { clash -> if (clash) warnClash(id) }
                    .thenReturn(storedView)
            }
            .switchIfEmpty(Mono.defer { configured.systemViews(tenantId, appId).filter { it.id == id }.next() })

    private fun warnClash(id: String) {
        if (warnedClashes.add(id)) {
            log.warn { "System view [$id] is both configured and stored: the stored one is served." }
        }
    }
}

/** The default [StoredSystemViewSource]: the view snapshots of the tenant `(platform)` and the owner `(system)`. */
internal class SnapshotStoredSystemViewSource(
    private val snapshotQueryGateway: () -> SnapshotQueryGateway<ViewState>,
) : StoredSystemViewSource {
    companion object {
        private val log = KotlinLogging.logger {}

        /** The most stored system views one application lists, the client's list budget. */
        const val MAX_STORED = 1000
        private const val DEFINITION_ID = "definitionId"
        private const val FIRST_EVENT_TIME = "firstEventTime"
        private const val AGGREGATE_ID = "aggregateId"
    }

    override fun storedSystemViews(appId: String, definitionId: String?, id: String?): Flux<SystemView> =
        query(appId, definitionId, id).collectList().flatMapMany { views ->
            if (views.size >= MAX_STORED) {
                log.warn {
                    "Application [$appId] has more stored system views than [$MAX_STORED]" +
                        (definitionId?.let { " of definition [$it]" } ?: "") + ": the oldest $MAX_STORED are served."
                }
            }
            Flux.fromIterable(views)
        }

    private fun query(appId: String, definitionId: String?, id: String?): Flux<SystemView> =
        listQuery {
            limit(MAX_STORED)
            filter {
                tenantId(SYSTEM_TENANT_ID)
                ownerId(SYSTEM_OWNER_ID)
                id?.let { id(it) }
                pathState {
                    ViewStoreQueryPolicy.APP_ID_FIELD eq appId
                    definitionId?.let { DEFINITION_ID eq it }
                }
            }
            sort {
                FIRST_EVENT_TIME.asc()
                AGGREGATE_ID.asc()
            }
        }.query(snapshotQueryGateway()).map { it.toSystemView() }

    private fun MaterializedSnapshot<ViewState>.toSystemView(): SystemView =
        SystemViews.of(aggregateId, state.definitionId, state.title, state.config)
            .copy(source = SystemViewSource.STORED, version = version)
}
