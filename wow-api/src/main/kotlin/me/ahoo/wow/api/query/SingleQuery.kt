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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

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
 * @property filter The filter expression to apply to the query.
 * @property projection The field projection to control which fields are included in the result.
 * @property sort The sorting criteria to order results (useful when multiple matches exist).
 *
 * ```
 * val query = SingleQuery(
 *     filter = EqualFilter(LogicalField("id"), value),
 *     projection = Projection(include = listOf("name", "email")),
 *     sort = listOf(Sort("createdDate", Direction.DESC))
 * )
 * ```
 */
data class SingleQuery(
    @get:JsonInclude(
        value = JsonInclude.Include.CUSTOM,
        valueFilter = LegacyConditionFilterValueFilter::class,
    )
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList()
) : ISingleQuery {
    @Deprecated("Use filter.")
    @get:JsonIgnore
    override val condition: Condition
        get() = filter.toLegacyCondition()

    @get:JsonProperty("condition")
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    internal val legacyConditionPayload: Condition?
        get() = filter.legacyConditionOrNull()

    @Deprecated("Use filter.")
    constructor(
        condition: Condition,
        projection: Projection = Projection.ALL,
        sort: List<Sort> = emptyList(),
    ) : this(LegacyConditionAdapter.adapt(condition), projection, sort)

    /**
     * Creates a new SingleQuery with the specified filter.
     *
     * @param newFilter The new filter to apply.
     * @return A new SingleQuery with the updated filter.
     */
    override fun withFilter(newFilter: FilterExpression): ISingleQuery = copy(filter = newFilter)

    @Deprecated("Use withFilter.")
    override fun withCondition(newCondition: Condition): ISingleQuery =
        copy(filter = LegacyConditionAdapter.adapt(newCondition))

    @Deprecated("Use appendFilter.")
    override fun appendCondition(append: Condition): ISingleQuery =
        copy(filter = LegacyConditionAdapter.adapt(condition.appendCondition(append)))

    @Deprecated("Use copy(filter = ...).")
    fun copy(
        condition: Condition,
        projection: Projection = this.projection,
        sort: List<Sort> = this.sort,
    ): SingleQuery = copy(filter = LegacyConditionAdapter.adapt(condition), projection = projection, sort = sort)

    /**
     * Creates a new SingleQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new SingleQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): ISingleQuery = copy(projection = newProjection)
}
