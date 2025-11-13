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

package me.ahoo.wow.api.query

/**
 * Interface for single-item queries that retrieve at most one result.
 *
 * This interface extends [Queryable] and is designed for queries that expect
 * to return a single item or null, rather than a collection of items.
 */
interface ISingleQuery : Queryable<ISingleQuery>

/**
 * Data class representing a query for retrieving a single item with optional filtering, projection, and sorting.
 *
 * This class implements [ISingleQuery] and provides a concrete implementation for single-item queries.
 * It supports all standard query operations but is optimized for scenarios where only one result is expected.
 *
 * @property condition The filtering condition to apply to the query.
 * @property projection The field projection to control which fields are included in the result.
 * @property sort The sorting criteria to order results (useful when multiple matches exist).
 *
 * @sample
 * ```
 * val query = SingleQuery(
 *     condition = Condition.eq("id", "user-123"),
 *     projection = Projection(include = listOf("name", "email")),
 *     sort = listOf(Sort("createdDate", Direction.DESC))
 * )
 * ```
 */
data class SingleQuery(
    override val condition: Condition,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList()
) : ISingleQuery {
    /**
     * Creates a new SingleQuery with the specified condition.
     *
     * @param newCondition The new condition to apply.
     * @return A new SingleQuery with the updated condition.
     */
    override fun withCondition(newCondition: Condition): ISingleQuery = copy(condition = newCondition)

    /**
     * Creates a new SingleQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new SingleQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): ISingleQuery = copy(projection = newProjection)
}
