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

import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.schema.QueryModelSchema
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
    /** The resolution of one field reference of [query], by the identity of its [QueryField] instance. */
    fun field(reference: QueryField): ResolvedField = resolved(reference)

    /** The resolution of the system field a system-field filter of [query] (ID, TENANT_ID, DELETION, ...) targets. */
    fun systemField(filter: FilterExpression): ResolvedField = resolved(filter)

    private fun resolved(node: Any): ResolvedField = fields[node]
        ?: throw IllegalArgumentException("[$node] is not a node of this admitted query.")

    /**
     * The same admission for a query the core derived from [query], such as the aggregation it sends a backend
     * after removing residual operators; [derived] must reuse this query's field nodes.
     */
    internal fun <D : Any> withQuery(derived: D): AdmittedQuery<D> = AdmittedQuery(derived, schema, entry, fields)

    override fun toString(): String = "AdmittedQuery(entry=$entry, model=${schema.model}, query=$query)"
}
