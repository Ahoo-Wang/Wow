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

@file:OptIn(
    me.ahoo.wow.query.backend.ExperimentalQueryBackendApi::class,
    me.ahoo.wow.query.gateway.ExperimentalQueryGatewayApi::class,
)

package me.ahoo.wow.spring.boot.starter.query

import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.QueryBackendComposition
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.backend.RecordQueryBackendNotReady
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import reactor.core.publisher.Mono

/** Storage-specific planned backend source. It is selected only after the existing aggregate storage route resolves. */
internal interface StorageQueryBackendSource {
    val storage: StorageType
    val targets: Set<QueryTarget>

    fun prepare(target: QueryTarget): Mono<StorageQueryBackendPreparation>
}

internal sealed interface StorageQueryBackendPreparation {
    data class Ready(val contribution: RecordQueryBackendContribution) : StorageQueryBackendPreparation

    data class NotReady(
        val schema: QueryDocumentSchema,
        val backendId: BackendId,
    ) : StorageQueryBackendPreparation
}

internal object StorageRoutedQueryBackendComposition {
    fun create(
        sources: List<StorageQueryBackendSource>,
        shouldPrepare: (QueryTarget) -> Boolean = { true },
        storageResolver: (QueryTarget) -> StorageType?,
    ): QueryBackendComposition {
        val sourcesByRoute = linkedMapOf<Pair<QueryTarget, StorageType>, StorageQueryBackendSource>()
        sources.forEach { source ->
            source.targets.forEach { target ->
                require(sourcesByRoute.put(target to source.storage, source) == null) {
                    "Duplicate planned Query Backend source for $target/${source.storage}."
                }
            }
        }
        val contributions = mutableListOf<RecordQueryBackendContribution>()
        val notReadyBackends = mutableListOf<RecordQueryBackendNotReady>()
        val routes = linkedMapOf<QueryTarget, me.ahoo.wow.query.backend.BackendId>()
        sourcesByRoute.entries.sortedWith(ROUTE_COMPARATOR).forEach { (route, source) ->
            val (target, storage) = route
            if (shouldPrepare(target) && storageResolver(target) == storage) {
                val preparation = requireNotNull(source.prepare(target).block()) {
                    "Planned Query Backend source returned empty for $target/$storage."
                }
                val schema = when (preparation) {
                    is StorageQueryBackendPreparation.Ready -> preparation.contribution.schema
                    is StorageQueryBackendPreparation.NotReady -> preparation.schema
                }
                require(schema.target == target) {
                    "Planned Query Backend contribution target does not match its storage route."
                }
                when (preparation) {
                    is StorageQueryBackendPreparation.Ready -> {
                        contributions += preparation.contribution
                        routes[target] = preparation.contribution.backendId
                    }

                    is StorageQueryBackendPreparation.NotReady -> {
                        notReadyBackends += RecordQueryBackendNotReady(schema, preparation.backendId)
                        routes[target] = preparation.backendId
                    }
                }
            }
        }
        return QueryBackendComposition(contributions, notReadyBackends, routes)
    }

    private val ROUTE_COMPARATOR = compareBy<Map.Entry<Pair<QueryTarget, StorageType>, StorageQueryBackendSource>> {
        it.key.first.namedAggregate.contextName
    }.thenBy { it.key.first.namedAggregate.aggregateName }
        .thenBy { it.key.first.documentKind.name }
        .thenBy { it.key.second.name }
}
