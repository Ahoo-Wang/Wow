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

package me.ahoo.wow.query.filter

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelSchema

/**
 * What a [QueryFilter] or [me.ahoo.wow.query.QueryPolicy] sees of the query being admitted.
 *
 * @param Q the query type.
 * @property query the query, after the filters before this one rewrote it.
 * @property aggregateType the aggregate type queried.
 * @property schema the query model schema the query is admitted against.
 * @property queryType the gateway operation (single, list, paged, cursor, count or aggregation).
 * @property entry where the query came from, as its [caller][me.ahoo.wow.query.QueryCaller] states.
 */
class QueryContext<Q : RewritableFilter<Q>> @InternalWowApi constructor(
    val query: Q,
    val aggregateType: AggregateType,
    val schema: QueryModelSchema,
    val queryType: QueryType,
    val entry: QueryEntry,
) {
    /** The model queried (`snapshot`, `events`, `dataset`), all a policy reads of the [schema] (review R3.2-3). */
    val model: QueryModel get() = schema.model

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryContext<*> && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "QueryContext(query=$query, aggregateType=$aggregateType, schema=$schema, queryType=$queryType, entry=$entry)"

    private fun valueFields(): List<Any?> = listOf(query, aggregateType, schema, queryType, entry)

    companion object {
        /** A [QueryContext] of the given values. */
        @JvmStatic
        fun <Q : RewritableFilter<Q>> of(
            query: Q,
            aggregateType: AggregateType,
            schema: QueryModelSchema,
            queryType: QueryType,
            entry: QueryEntry,
        ): QueryContext<Q> = QueryContext(query, aggregateType, schema, queryType, entry)
    }
}
