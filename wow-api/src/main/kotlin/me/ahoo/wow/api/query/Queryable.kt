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

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Data class representing a sorting criterion for query results.
 *
 * This class defines how query results should be ordered based on a specific field
 * and sort direction.
 *
 * @property field The name of the field to sort by.
 * @property direction The direction of sorting (ascending or descending).
 *
 * ```
 * val sortByName = Sort(QueryField("name"), Sort.Direction.ASC)
 * val sortByDate = Sort(QueryField("createdDate"), Sort.Direction.DESC)
 * ```
 */
data class Sort(
    val field: QueryField,
    val direction: Direction
) {
    /**
     * Enumeration of possible sort directions.
     */
    enum class Direction {
        /**
         * Ascending sort order (A to Z, 1 to 9).
         */
        ASC,

        /**
         * Descending sort order (Z to A, 9 to 1).
         */
        DESC
    }
}

/**
 * Data class representing pagination settings for queries.
 *
 * This class defines which page of results to retrieve and how many items per page.
 * Page indexing starts from 1, and the offset is calculated automatically.
 *
 * @property index The page number to retrieve (1-based indexing).
 * @property size The number of items per page.
 *
 * ```
 * val firstPage = Pagination(index = 1, size = 20)  // First 20 items
 * val secondPage = Pagination(index = 2, size = 20) // Items 21-40
 * ```
 */
data class Pagination(
    @field:Schema(defaultValue = "1")
    val index: Int = 1,
    @field:Schema(defaultValue = "10")
    val size: Int = 10,
) {
    companion object {
        /**
         * Default pagination settings (page 1, 10 items per page).
         */
        val DEFAULT = Pagination()

        /**
         * Calculates the offset for a given page index and size.
         *
         * @param index The page number (1-based).
         * @param size The number of items per page.
         * @return The zero-based offset for database queries.
         */
        fun offset(
            index: Int,
            size: Int
        ) = (index - 1) * size
    }

    /**
     * Calculates the zero-based offset for this pagination settings.
     *
     * @return The offset value for database queries.
     */
    fun offset() = offset(index, size)
}

/**
 * Data class representing field projection settings for queries.
 *
 * Projection controls which fields are included in or excluded from query results.
 * Use include to select nodes and their descendants, or exclude to omit them.
 * If both lists are empty, all fields are included. Combining include and exclude follows backend restrictions.
 *
 * MongoDB single/list/paged queries retain the native `_id` field by default even when include omits it.
 * It is returned as `aggregateId` for snapshots and `id` for event streams. Explicitly exclude that logical
 * field to suppress it; MongoDB permits this ID exclusion alongside include. Elasticsearch does not add
 * an unrequested ID. Cursor queries remove fields fetched only for sorting, including an unrequested ID.
 *
 * @property include Nodes to return, subject to the MongoDB ID exception above.
 * @property exclude Nodes to omit, including the logical ID when it should not be returned.
 *
 * ```
 * val includeOnly = Projection(include = listOf(QueryField("name"), QueryField("email")))  // MongoDB also retains ID
 * val excludeSome = Projection(exclude = listOf(QueryField("password"), QueryField("secret")))  // Return all except password and secret
 * val allFields = Projection.ALL  // Return all fields
 * ```
 */
data class Projection(
    @field:Schema(defaultValue = "[]")
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val include: List<QueryField> = emptyList(),
    @field:Schema(defaultValue = "[]")
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    val exclude: List<QueryField> = emptyList()
) {
    companion object {
        /**
         * Projection that includes all fields (no filtering).
         */
        val ALL = Projection()
    }
}

/**
 * Checks if this projection has no field filtering (includes all fields).
 *
 * @return true if both include and exclude lists are empty, false otherwise.
 */
fun Projection.isEmpty(): Boolean = include.isEmpty() && exclude.isEmpty()

/**
 * Interface for query objects that support comprehensive querying capabilities.
 *
 * This interface combines condition filtering, field projection, and sorting capabilities
 * to provide a complete query interface for retrieving and filtering data.
 *
 * @param Q The type of the query object that implements this interface, enabling method chaining.
 */
interface Queryable<Q : Queryable<Q>> :
    FilterCapable<Q>,
    ProjectionCapable<Q>,
    SortCapable
