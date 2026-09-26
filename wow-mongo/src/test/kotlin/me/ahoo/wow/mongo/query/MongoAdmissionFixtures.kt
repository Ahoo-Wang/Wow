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

package me.ahoo.wow.mongo.query

import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.schema.QueryModelSchema
import org.bson.conversions.Bson

/*
 * Compiler fixtures: every native compiler consumes an admitted query, so these admit the logical input first,
 * exactly as the gateway and the backends do.
 */

internal fun AbstractMongoFilterCompiler.compile(filter: FilterExpression, schema: QueryModelSchema): Bson =
    compile(QueryAdmission.Trusted.count(filter, schema))

internal fun MongoAggregationCompiler.compile(query: AggregationQuery, schema: QueryModelSchema): List<Bson> =
    compile(QueryAdmission.Trusted.aggregate(query, schema))

internal fun MongoSortCompiler.compile(sort: List<Sort>, schema: QueryModelSchema): Bson? =
    QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, sort = sort), schema).let { compile(it.query.sort, it) }

internal fun MongoProjectionCompiler.compile(projection: Projection, schema: QueryModelSchema): Bson? =
    QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, projection), schema).let { compile(it.query.projection, it) }

internal fun MongoProjectionCompiler.cursorProjection(
    projection: Projection,
    sortFields: List<String>,
    schema: QueryModelSchema,
): MongoCursorProjection = QueryAdmission.Trusted.list(ListQuery(MatchAllFilter, projection), schema).let {
    cursorProjection(it.query.projection, sortFields, it)
}
