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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import me.ahoo.wow.elasticsearch.query.planned.ElasticsearchQueryBackendNotReadyException
import me.ahoo.wow.elasticsearch.query.planned.ElasticsearchSnapshotQueryBinding
import me.ahoo.wow.elasticsearch.query.planned.prepareContribution
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.query.StorageQueryBackendPreparation
import me.ahoo.wow.spring.boot.starter.query.StorageQueryBackendSource
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.LinkedHashMap

internal class ElasticsearchPlannedQueryBackendSource(
    private val client: ReactiveElasticsearchClient,
    bindings: List<ElasticsearchSnapshotQueryBinding>,
) : StorageQueryBackendSource {
    override val storage: StorageType = StorageType.ELASTICSEARCH
    private val bindings: Map<QueryTarget, ElasticsearchSnapshotQueryBinding>
    override val targets: Set<QueryTarget>

    init {
        val copy = LinkedHashMap<QueryTarget, ElasticsearchSnapshotQueryBinding>(bindings.size)
        bindings.forEach { binding ->
            require(copy.put(binding.schema.target, binding) == null) {
                "Elasticsearch planned Query bindings must be unique per target[${binding.schema.target}]."
            }
        }
        this.bindings = Collections.unmodifiableMap(copy)
        targets = Collections.unmodifiableSet(LinkedHashSet(copy.keys))
    }

    override fun prepare(target: QueryTarget): Mono<StorageQueryBackendPreparation> {
        val binding = requireNotNull(bindings[target]) {
            "Elasticsearch planned Query binding is not registered for target[$target]."
        }
        return binding.prepareContribution(client)
            .map<StorageQueryBackendPreparation>(StorageQueryBackendPreparation::Ready)
            .onErrorResume(ElasticsearchQueryBackendNotReadyException::class.java) {
                Mono.just(
                    StorageQueryBackendPreparation.NotReady(
                        binding.schema,
                        binding.backendId,
                    ),
                )
            }
    }
}
