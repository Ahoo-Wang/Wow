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

package me.ahoo.wow.spring.boot.starter.mongo

import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.mongo.query.planned.MongoEventStreamQueryBinding
import me.ahoo.wow.mongo.query.planned.MongoQueryBackendNotReadyException
import me.ahoo.wow.mongo.query.planned.MongoSnapshotQueryBinding
import me.ahoo.wow.mongo.query.planned.prepareContribution
import me.ahoo.wow.query.backend.BackendId
import me.ahoo.wow.query.backend.QueryDocumentSchema
import me.ahoo.wow.query.backend.RecordQueryBackendContribution
import me.ahoo.wow.query.gateway.QueryTarget
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.query.StorageQueryBackendPreparation
import me.ahoo.wow.spring.boot.starter.query.StorageQueryBackendSource
import reactor.core.publisher.Mono
import java.util.Collections
import java.util.LinkedHashMap

internal class MongoPlannedQueryBackendSource private constructor(
    private val database: MongoDatabase,
    bindings: Map<QueryTarget, MongoPlannedBinding>,
) : StorageQueryBackendSource {
    override val storage: StorageType = StorageType.MONGO
    private val bindings: Map<QueryTarget, MongoPlannedBinding> = Collections.unmodifiableMap(LinkedHashMap(bindings))
    override val targets: Set<QueryTarget>

    init {
        require(this.bindings.keys == this.bindings.values.mapTo(linkedSetOf()) { binding -> binding.schema.target }) {
            "Mongo planned Query binding keys must match their schema targets."
        }
        targets = Collections.unmodifiableSet(LinkedHashSet(this.bindings.keys))
    }

    override fun prepare(target: QueryTarget): Mono<StorageQueryBackendPreparation> {
        val binding = requireNotNull(bindings[target]) {
            "Mongo planned Query binding is not registered for target[$target]."
        }
        require(binding.namespace.databaseName == database.name) {
            "Mongo planned Query binding namespace[${binding.namespace}] must use database[${database.name}]."
        }
        return binding.prepare(
            database.getCollection(binding.namespace.collectionName),
        ).map<StorageQueryBackendPreparation> { contribution ->
            StorageQueryBackendPreparation.Ready(contribution)
        }.onErrorResume(MongoQueryBackendNotReadyException::class.java) {
            Mono.just(
                StorageQueryBackendPreparation.NotReady(
                    binding.schema,
                    binding.backendId,
                ),
            )
        }
    }

    companion object {
        fun snapshot(
            database: MongoDatabase,
            bindings: List<MongoSnapshotQueryBinding>,
        ): MongoPlannedQueryBackendSource = MongoPlannedQueryBackendSource(
            database,
            uniqueBindings(
                bindings.map { binding ->
                    MongoPlannedBinding(
                        binding.schema,
                        binding.namespace,
                        binding.backendId,
                        binding::prepareContribution,
                    )
                },
            ),
        )

        fun eventStream(
            database: MongoDatabase,
            bindings: List<MongoEventStreamQueryBinding>,
        ): MongoPlannedQueryBackendSource = MongoPlannedQueryBackendSource(
            database,
            uniqueBindings(
                bindings.map { binding ->
                    MongoPlannedBinding(
                        binding.schema,
                        binding.namespace,
                        binding.backendId,
                        binding::prepareContribution,
                    )
                },
            ),
        )

        private fun uniqueBindings(bindings: List<MongoPlannedBinding>): Map<QueryTarget, MongoPlannedBinding> {
            val result = LinkedHashMap<QueryTarget, MongoPlannedBinding>(bindings.size)
            bindings.forEach { binding ->
                require(result.put(binding.schema.target, binding) == null) {
                    "Mongo planned Query bindings must be unique per target[${binding.schema.target}]."
                }
            }
            return result
        }
    }
}

private class MongoPlannedBinding(
    val schema: QueryDocumentSchema,
    val namespace: com.mongodb.MongoNamespace,
    val backendId: BackendId,
    val prepare: (com.mongodb.reactivestreams.client.MongoCollection<org.bson.Document>) ->
    Mono<RecordQueryBackendContribution>,
)
