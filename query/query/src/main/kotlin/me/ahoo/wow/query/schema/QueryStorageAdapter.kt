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

import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.annotation.WowSpi

/**
 * A storage's port into the Catalog (design §5.2, §5.5): it reports what its native structures (indexes, mappings,
 * validators) prove about a logical model, and nothing else. The Catalog merges the model's sources and compiles the
 * reported facts into a [QueryModelSchema], applying every rule that does not depend on the storage.
 */
@WowSpi
@InternalWowApi
interface QueryStorageAdapter {
    /** The storage's facts about [logicalSchema], from its current native structures. */
    suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts

    /** The storage's facts about [logicalSchema], reloading native structures it caches. */
    suspend fun refresh(logicalSchema: LogicalQuerySchema): QueryStorageFacts = facts(logicalSchema)
}

/**
 * The storage adapter of an aggregate model that has no query backend: every load fails with [message].
 * [QuerySchemaCatalog] skips it, since there is nothing to load or revalidate.
 */
internal class UnavailableQueryStorageAdapter(val message: String) : QueryStorageAdapter {
    override suspend fun facts(logicalSchema: LogicalQuerySchema): QueryStorageFacts =
        throw QuerySchemaUnavailableException(message)
}

/**
 * What a storage proves about one read model, in the storage-neutral vocabulary of capabilities: for each logical
 * path, the capabilities its native structures can execute and the physical location each binds to, with the
 * projection and response locations; and the storage's model-wide support. Which native types, index kinds or
 * mapping options prove a capability is the storage's knowledge; what a logical value must be for a capability to
 * apply at all is the Catalog's ([compile]).
 */
@InternalWowApi
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
    /**
     * Whether the facts describe storage that does not exist yet, such as an index before its first write, from what
     * it will be created with. A provisional schema is never published: the next load asks the storage again, so the
     * schema follows the storage as soon as it exists.
     */
    val provisional: Boolean = false,
) {
    /**
     * Compiles these facts about [logicalSchema] into the [model]'s schema. The storage-independent capability rules
     * apply here, once for every storage:
     * - a cursor orders by one value per record, so no path inside an array or with an array alternative is
     *   cursor-sortable;
     * - temporal aggregation reads instants, so it needs one date or epoch encoding shared by every value;
     * - an element scope is an array of objects, a union of objects included ([isElementScope]); storages only say
     *   whether their native structures can match one element;
     * - a multi-valued value (any array alternative) has no single number or instant per record, so it is granted
     *   no numeric or temporal aggregation (review X-8: a root sum over an array silently under-counted); terms still
     *   group it by each of its values. A value inside an element scope is single-valued there: expand the element
     *   (`AggregationQuery.elements`) to aggregate it.
     */
    @JvmOverloads
    fun compile(
        model: QueryModel,
        logicalSchema: LogicalQuerySchema,
        profile: QueryModelProfile? = QueryModelProfile.of(model),
    ): QueryModelSchema = QueryModelSchema(
        model = model,
        capabilities = capabilities,
        definition = logicalSchema,
        bindings = bindings.mapValues { (path, native) -> native.compile(path, logicalSchema.value(path)) },
        fullProjectionAvailable = fullProjectionAvailable,
        approximateMetrics = approximateMetrics,
        storage = storage,
        provisional = provisional,
        profile = profile,
    )

    private fun QueryValueBindings.compile(path: QueryPathTemplate, value: QueryValueSchema?): QueryValueBindings {
        if (value == null) return this
        val admitted = bindings.filterKeys { capability ->
            when (capability) {
                QueryCapability.CURSOR_SORT -> path.segments.none { it == QueryPathSegment.Item } && !value.hasArrayBranch()
                QueryCapability.AGGREGATE_TEMPORAL -> value.sharedTemporal().encodesInstant
                QueryCapability.ELEMENT_SCOPE -> value.isElementScope()
                QueryCapability.AGGREGATE_NUMERIC, QueryCapability.AGGREGATE_TEMPORAL -> !value.hasArrayBranch()
                else -> true
            }
        }
        return if (admitted.size == bindings.size) this else QueryValueBindings(admitted, projectionPath, responsePath)
    }
}

/**
 * Whether this value can be an element scope: every non-null alternative is an array whose non-null items are all
 * objects, so a predicate can match one element as a whole.
 */
internal fun QueryValueSchema.isElementScope(): Boolean = alternativesOrSelf().filter { it.kind != QueryValueKind.NULL }
    .let { branches ->
        branches.isNotEmpty() && branches.all { branch ->
            branch.kind == QueryValueKind.ARRAY && checkNotNull(branch.items).alternativesOrSelf()
                .filter { it.kind != QueryValueKind.NULL }
                .let { items -> items.isNotEmpty() && items.all { it.kind == QueryValueKind.OBJECT } }
        }
    }
