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

package me.ahoo.wow.consumer

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendBinding
import me.ahoo.wow.query.index.QueryIndexPlan
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.AggregationSupport
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryCapability
import me.ahoo.wow.query.schema.QueryCardinality
import me.ahoo.wow.query.schema.QueryFieldBindingTemplate
import me.ahoo.wow.query.schema.QueryStorageAdapter
import me.ahoo.wow.query.schema.QueryStorageFacts
import me.ahoo.wow.query.schema.QueryValueBindings
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.StorageSupport
import me.ahoo.wow.query.schema.SupportMode
import me.ahoo.wow.query.schema.alternativesOrSelf
import me.ahoo.wow.query.schema.operationValues
import me.ahoo.wow.query.schema.storageFamilies
import me.ahoo.wow.query.store.QueryIndexReport
import me.ahoo.wow.query.store.SnapshotQueries
import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.query.store.StoreQueryOptions
import java.util.concurrent.ConcurrentHashMap

/**
 * The query side of an `acme-snapshots` store: its snapshots' queries over the in-memory reference backend, with the
 * facts of Acme's own [AcmeStorageAdapter], and the indexes the aggregates declare, which Acme keeps as names.
 */
class AcmeQueries(private val storage: InMemoryQueryStorage) : StoreQueries {
    private val indexes = ConcurrentHashMap<AggregateType, Set<String>>()

    override fun snapshots(options: StoreQueryOptions): SnapshotQueries = object : SnapshotQueries {
        override fun binding(key: AggregateType): QueryBackendBinding<QueryBackend> =
            QueryBackendBinding.of(storage.binding(key).backend, AcmeStorageAdapter)

        override val managesIndexes: Boolean get() = true

        // region indexes
        override fun manageIndexes(type: AggregateType, plan: QueryIndexPlan): QueryIndexReport {
            val declared = plan.indexes.map { index ->
                // Acme's rule on top of the plan's: an index keys one value per record.
                index.keys.forEach { key ->
                    require(key.cardinality == QueryCardinality.SINGLE) {
                        "Index [${index.name}] has the key [${key.field}], which holds many values."
                    }
                }
                index.name
            }.toSet()
            val existing = indexes.put(type, declared).orEmpty()
            return QueryIndexReport(created = (declared - existing).toList())
        }
        // endregion indexes
    }
}

/**
 * What Acme's storage serves, in the public vocabulary alone: every path of the model with presence, an element
 * scope where it is an array, and each value capability whose storage families its scalar values all have.
 */
object AcmeStorageAdapter : QueryStorageAdapter {
    private val VALUE_CAPABILITIES = listOf(
        QueryCapability.EXACT_MATCH,
        QueryCapability.LITERAL_MATCH,
        QueryCapability.RANGE,
        QueryCapability.SORT,
        QueryCapability.CURSOR_SORT,
        QueryCapability.AGGREGATE_TERMS,
        QueryCapability.AGGREGATE_NUMERIC,
        QueryCapability.AGGREGATE_TEMPORAL,
    )

    // region facts
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts = QueryStorageFacts.build {
        logicalSchema.values.filterKeys { it.segments.isNotEmpty() }.forEach { (path, value) ->
            val stored = QueryFieldBindingTemplate.of(path, storageTypes = null)
            binding(path, QueryValueBindings.of(capabilitiesOf(value).associateWith { stored }, path, path))
        }
        storage(
            StorageSupport.of(
                aggregation = AggregationSupport.of(
                    having = SupportMode.RESIDUAL,
                    topN = SupportMode.RESIDUAL,
                    denseFill = SupportMode.RESIDUAL,
                ),
            ),
        )
    }
    // endregion facts

    private fun capabilitiesOf(value: QueryValueSchema): Set<QueryCapability> {
        val capabilities = linkedSetOf(QueryCapability.PRESENCE)
        if (value.alternativesOrSelf().any { it.kind == QueryValueKind.ARRAY }) {
            capabilities += QueryCapability.ELEMENT_SCOPE
        }
        val scalars = value.operationValues().filter { it.kind != QueryValueKind.NULL }
        if (scalars.isEmpty() || scalars.any { it.kind != QueryValueKind.SCALAR }) return capabilities
        VALUE_CAPABILITIES.forEach { capability ->
            val families = scalars.map { it.storageFamilies(capability) }
            if (families.all { sets -> sets.isNotEmpty() && sets.all(Set<*>::isNotEmpty) }) capabilities += capability
        }
        return capabilities
    }
}
