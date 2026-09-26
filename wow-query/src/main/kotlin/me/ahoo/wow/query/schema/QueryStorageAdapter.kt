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

package me.ahoo.wow.query.schema

import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.Temporal
import reactor.core.publisher.Mono

/**
 * A storage's port into the Catalog (design §5.2, §5.5): it reports what its native structures (indexes, mappings,
 * validators) prove about a logical model, and nothing else. The Catalog merges the model's sources and compiles the
 * reported facts into a [QueryModelSchema], applying every rule that does not depend on the storage.
 */
fun interface QueryStorageAdapter {
    /** The storage's facts about [logicalSchema], from its current native structures. */
    fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts>

    /** The storage's facts about [logicalSchema], reloading native structures it caches. */
    fun refresh(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> = facts(logicalSchema)
}

/**
 * The storage adapter of an aggregate model that has no query backend: every load fails with [message].
 * [QuerySchemaCatalog] skips it, since there is nothing to load or revalidate.
 */
class UnavailableQueryStorageAdapter(val message: String) : QueryStorageAdapter {
    override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> =
        Mono.error(QuerySchemaUnavailableException(message))
}

/**
 * What a storage proves about one read model, in the storage-neutral vocabulary of capabilities: for each logical
 * path, the capabilities its native structures can execute and the physical location each binds to, with the
 * projection and response locations; and the storage's model-wide support. Which native types, index kinds or
 * mapping options prove a capability is the storage's knowledge; what a logical value must be for a capability to
 * apply at all is the Catalog's ([compile]).
 */
class QueryStorageFacts(
    val bindings: Map<QueryPathTemplate, QueryValueBindings>,
    /** The model-wide capabilities the storage proves, such as a full-text search over the whole record. */
    val capabilities: Set<QueryCapability> = emptySet(),
    /** Whether native storage can deliver an unrestricted source projection. */
    val fullProjectionAvailable: Boolean = true,
    /** The metric types (`DISTINCT_COUNT`, `PERCENTILE`) the storage estimates rather than computes exactly. */
    val approximateMetrics: Set<String> = emptySet(),
    /** How the storage pages and aggregates. */
    val storage: StorageSupport = StorageSupport.NATIVE,
) {
    /**
     * Compiles these facts about [logicalSchema] into the [model]'s schema. The storage-independent capability rules
     * apply here, once for every storage:
     * - a cursor orders by one value per record, so no path inside an array or with an array alternative is
     *   cursor-sortable;
     * - temporal aggregation reads instants, so it needs one date or epoch encoding shared by every value;
     * - an element scope is an array of objects.
     */
    fun compile(model: QueryModel, logicalSchema: LogicalQuerySchema): QueryModelSchema = QueryModelSchema(
        model = model,
        capabilities = capabilities,
        definition = logicalSchema,
        bindings = bindings.mapValues { (path, native) -> native.compile(path, logicalSchema.value(path)) },
        fullProjectionAvailable = fullProjectionAvailable,
        approximateMetrics = approximateMetrics,
        storage = storage,
    )

    private fun QueryValueBindings.compile(path: QueryPathTemplate, value: QueryValueSchema?): QueryValueBindings {
        if (value == null) return this
        val admitted = bindings.filterKeys { capability ->
            when (capability) {
                QueryCapability.CURSOR_SORT -> path.segments.none { it == QueryPathSegment.Item } && !value.hasArrayBranch()
                QueryCapability.AGGREGATE_TEMPORAL -> value.instantEncoded()
                QueryCapability.ELEMENT_SCOPE -> value.isElementScope()
                else -> true
            }
        }
        return if (admitted.size == bindings.size) this else QueryValueBindings(admitted, projectionPath, responsePath)
    }

    private fun QueryValueSchema.instantEncoded(): Boolean {
        val temporal = operationValues().filter { it.kind != QueryValueKind.NULL }.map { it.semanticType }.distinct()
            .singleOrNull()
        return temporal == Temporal.Date || temporal is Temporal.Epoch
    }
}

/**
 * Whether this value can be an element scope: every non-null alternative is an array whose non-null items are all
 * objects, so a predicate can match one element as a whole.
 */
fun QueryValueSchema.isElementScope(): Boolean = alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
    .let { branches ->
        branches.isNotEmpty() && branches.all { branch ->
            branch.kind == QueryValueKind.ARRAY && checkNotNull(branch.items).alternativesOrSelf()
                .filter { it.kind != QueryValueKind.NULL }
                .let { items -> items.isNotEmpty() && items.all { it.kind == QueryValueKind.OBJECT } }
        }
    }
