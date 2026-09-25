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

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.RewritableFilter
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.schema.QueryModelSchema

/**
 * What a [QueryFilter] or [me.ahoo.wow.query.QueryPolicy] sees of the query being admitted.
 *
 * @param Q the query type.
 * @property query the query, after the filters before this one rewrote it.
 * @property namedAggregate the aggregate queried.
 * @property schema the query model schema the query is admitted against.
 * @property queryType the gateway operation (single, list, paged, cursor, count or aggregation).
 * @property entry where the query came from, read once when it was subscribed.
 */
data class QueryContext<Q : RewritableFilter<Q>>(
    val query: Q,
    val namedAggregate: NamedAggregate,
    val schema: QueryModelSchema,
    val queryType: QueryType,
    val entry: QueryEntry,
)
