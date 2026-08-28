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
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Interface for list queries that retrieve multiple items with a limit.
 *
 * This interface extends [Queryable] and adds a limit parameter to control
 * the maximum number of items returned by the query.
 */
interface IListQuery : Queryable<IListQuery> {
    /**
     * The maximum number of items to return in the query result.
     * Must be >= 0. If set to 0, the query will return unlimited results.
     */
    @get:Schema(defaultValue = "0", minimum = "0")
    val limit: Int
}

/**
 * Data class representing a query for retrieving a list of items with optional filtering, projection, sorting, and limiting.
 *
 * This class implements [IListQuery] and provides a concrete implementation for list-based queries.
 * It supports all standard query operations including conditions, projections, sorting, and result limiting.
 *
 * @property filter The filter expression to apply to the query.
 * @property projection The field projection to control which fields are included in the results.
 * @property sort The sorting criteria to order the results.
 * @property limit The maximum number of items to return. Defaults to the standard pagination size.
 *
 * ```
 * val query = ListQuery(
 *     filter = EqualFilter(LogicalField("status"), value),
 *     projection = Projection(listOf("name", "email")),
 *     sort = listOf(Sort("name", Direction.ASC)),
 *     limit = 50
 * )
 * ```
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class ListQuery(
    @get:JsonIgnore(false)
    override val filter: FilterExpression,
    override val projection: Projection = Projection.ALL,
    override val sort: List<Sort> = emptyList(),
    override val limit: Int = 0
) : IListQuery {
    private constructor(
        @JsonProperty("filter") @JsonSetter(nulls = Nulls.FAIL) filter: FilterExpression? = null,
        @JsonProperty("condition") @JsonSetter(nulls = Nulls.FAIL) condition: FilterExpression? = null,
        @JsonProperty("projection") projection: Projection = Projection.ALL,
        @JsonProperty("sort") sort: List<Sort> = emptyList(),
        @JsonProperty("limit") limit: Int = 0,
    ) : this(resolveCompatibleFilter(filter, condition), projection, sort, limit)

    @Deprecated("Use filter.")
    constructor(
        condition: Condition,
        projection: Projection = Projection.ALL,
        sort: List<Sort> = emptyList(),
        limit: Int = 0,
    ) : this(condition.toFilterExpression(), projection, sort, limit)

    /**
     * Creates a new ListQuery with the specified filter.
     *
     * @param newFilter The new filter to apply.
     * @return A new ListQuery with the updated filter.
     */
    override fun withFilter(newFilter: FilterExpression): IListQuery = copy(filter = newFilter)

    /**
     * Creates a new ListQuery with the specified projection.
     *
     * @param newProjection The new projection to apply.
     * @return A new ListQuery with the updated projection.
     */
    override fun withProjection(newProjection: Projection): IListQuery = copy(projection = newProjection)
}
