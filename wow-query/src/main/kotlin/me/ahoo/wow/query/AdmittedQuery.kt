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

package me.ahoo.wow.query

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterCapable
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.requireIdentityField
import me.ahoo.wow.query.schema.validateQuery
import java.time.Instant
import java.util.IdentityHashMap

/**
 * A query that admission let through for one subscription, with the schema it was admitted against, the entry it
 * ran under and the resolution of every field reference it carries. Only [QueryAdmission] creates it, so a
 * [QueryBackend] cannot receive a query that skipped admission.
 *
 * [query] is the normalized query in its original AST types. Every node of it that carries a field is a fresh
 * instance, and [field] / [systemField] answer that node's [ResolvedField] by identity: backends read resolutions
 * instead of looking fields up. Later admission facts are added as further properties.
 */
class AdmittedQuery<out Q : Any> internal constructor(
    val query: Q,
    val schema: QueryModelSchema,
    val entry: QueryEntry,
    private val fields: IdentityHashMap<Any, ResolvedField>,
) {
    operator fun component1(): Q = query

    operator fun component2(): QueryModelSchema = schema

    /** The resolution of one field reference of [query], by the identity of its [QueryField] instance. */
    fun field(reference: QueryField): ResolvedField = resolved(reference)

    /** The resolution of the system field a system-field filter of [query] (ID, TENANT_ID, DELETION, ...) targets. */
    fun systemField(filter: FilterExpression): ResolvedField = resolved(filter)

    private fun resolved(node: Any): ResolvedField = fields[node]
        ?: throw IllegalArgumentException("[$node] is not a node of this admitted query.")

    override fun toString(): String = "AdmittedQuery(entry=$entry, model=${schema.model}, query=$query)"
}

/**
 * The last admission steps, shared by the gateway and by low-level callers (backend conformance tests, tools) that
 * drive a [QueryBackend] directly: the operation's finishing touches (a cursor's unique tie-breaker sort), validation
 * against the schema, normalization and field resolution. Normalization resolves relative time against one server
 * `now` per admitted query, encoded as each field stores time, lowers derived operators and simplifies logical nodes,
 * so every condition of one query sees the same moment. Resolution then rebuilds every field-carrying node as a fresh
 * instance and registers its [ResolvedField], so backends receive a finished logical query with its physical facts.
 *
 * The gateway runs the earlier steps first (entry budget, [me.ahoo.wow.query.filter.QueryFilter] rewrites, the
 * caller's scope, [QueryPolicy] conditions and the model's default scope); these functions do not, so a direct
 * caller gets exactly the query it wrote, validated, normalized and resolved.
 */
object QueryAdmission {
    private val normalizer = FilterNormalizer()

    @JvmStatic
    @JvmOverloads
    fun single(query: ISingleQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        admit(schema, entry) { single(validateQuery(query, schema).normalized(schema)) }

    @JvmStatic
    @JvmOverloads
    fun list(query: IListQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        admit(schema, entry) { list(validateQuery(query, schema).normalized(schema)) }

    @JvmStatic
    @JvmOverloads
    fun paged(query: IPagedQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        admit(schema, entry) { paged(validateQuery(query, schema).normalized(schema)) }

    /** Appends the model's identity field as the unique tie-breaker sort before validating. */
    @JvmStatic
    @JvmOverloads
    fun cursor(query: ICursorQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        admit(schema, entry) {
            cursor(validateQuery(query.withUniqueSort(schema.requireIdentityField()), schema).normalized(schema))
        }

    @JvmStatic
    @JvmOverloads
    fun count(filter: FilterExpression, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        admit(schema, entry) { filter(normalizer.normalize(validateQuery(filter, schema), schema, null, now())) }

    @JvmStatic
    @JvmOverloads
    fun aggregate(query: AggregationQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        admit(schema, entry) { aggregate(normalizer.normalize(validateQuery(query, schema), schema, now())) }

    private inline fun <Q : Any> admit(
        schema: QueryModelSchema,
        entry: QueryEntry,
        resolve: FieldResolver.() -> Q,
    ): AdmittedQuery<Q> {
        val resolver = FieldResolver(schema)
        val query = resolver.resolve()
        return AdmittedQuery(query, schema, entry, resolver.fields)
    }

    private fun now(): Instant = Instant.now()

    private fun <Q : FilterCapable<Q>> Q.normalized(schema: QueryModelSchema): Q {
        val normalized = normalizer.normalize(filter, schema, null, now())
        return if (normalized === filter) this else withFilter(normalized)
    }
}
