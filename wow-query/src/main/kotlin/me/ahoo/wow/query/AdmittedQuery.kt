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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.requireIdentityField
import me.ahoo.wow.query.schema.validateQuery

/**
 * A query that admission let through for one subscription, with the schema it was admitted against and the entry it
 * ran under. Only [QueryAdmission] creates it, so a [QueryBackend] cannot receive a query that skipped admission.
 */
class AdmittedQuery<out Q : Any> internal constructor(
    val query: Q,
    val schema: QueryModelSchema,
    val entry: QueryEntry,
) {
    operator fun component1(): Q = query

    operator fun component2(): QueryModelSchema = schema

    override fun toString(): String = "AdmittedQuery(entry=$entry, model=${schema.model}, query=$query)"
}

/**
 * The last admission steps, shared by the gateway and by low-level callers (backend conformance tests, tools) that
 * drive a [QueryBackend] directly: the operation's finishing touches (a cursor's unique tie-breaker sort) and
 * validation against the schema.
 *
 * The gateway runs the earlier steps first (entry budget, [me.ahoo.wow.query.filter.QueryFilter] rewrites, the
 * caller's scope, [QueryPolicy] conditions and the model's default scope); these functions do not, so a direct
 * caller gets exactly the query it wrote, validated.
 */
object QueryAdmission {
    @JvmStatic
    @JvmOverloads
    fun single(query: ISingleQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        AdmittedQuery(validateQuery(query, schema), schema, entry)

    @JvmStatic
    @JvmOverloads
    fun list(query: IListQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        AdmittedQuery(validateQuery(query, schema), schema, entry)

    @JvmStatic
    @JvmOverloads
    fun paged(query: IPagedQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        AdmittedQuery(validateQuery(query, schema), schema, entry)

    /** Appends the model's identity field as the unique tie-breaker sort before validating. */
    @JvmStatic
    @JvmOverloads
    fun cursor(query: ICursorQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        AdmittedQuery(validateQuery(query.withUniqueSort(schema.requireIdentityField()), schema), schema, entry)

    @JvmStatic
    @JvmOverloads
    fun count(filter: FilterExpression, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        AdmittedQuery(validateQuery(filter, schema), schema, entry)

    @JvmStatic
    @JvmOverloads
    fun aggregate(query: AggregationQuery, schema: QueryModelSchema, entry: QueryEntry = QueryEntry.IN_PROCESS) =
        AdmittedQuery(validateQuery(query, schema), schema, entry)
}
