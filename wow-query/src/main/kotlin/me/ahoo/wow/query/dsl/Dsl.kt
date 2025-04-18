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

package me.ahoo.wow.query.dsl

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort

/**
 * Executes a single query using the provided DSL block.
 *
 * This method takes a lambda that configures a [SingleQueryDsl] instance. The DSL allows
 * for defining the conditions, projections, and sorting for the query. After the DSL block
 * is executed, the method builds and returns an [ISingleQuery] object that can be used to
 * execute the query.
 *
 * @param block A lambda that receives a [SingleQueryDsl] and configures the query.
 * @return An [ISingleQuery] representing the configured query.
 *
 */
fun singleQuery(block: SingleQueryDsl.() -> Unit): ISingleQuery {
    val dsl = SingleQueryDsl()
    dsl.block()
    return dsl.build()
}

/**
 * Constructs a list query using the provided [ListQueryDsl] block.
 *
 * This function initializes a [ListQueryDsl] and applies the configurations
 * defined within the `block` to it. The resulting [IListQuery] object encapsulates
 * the query parameters, such as limit, sort, and conditions, which are set through the DSL.
 *
 * @param block A lambda that receives a [ListQueryDsl] instance for configuring the query.
 * @return An [IListQuery] representing the constructed query with all the specified configurations.
 */
fun listQuery(block: ListQueryDsl.() -> Unit): IListQuery {
    val dsl = ListQueryDsl()
    dsl.block()
    return dsl.build()
}

/**
 * Executes a paged query using the provided DSL block to configure pagination, sorting, and conditions.
 *
 * @param block A lambda that receives a [PagedQueryDsl] and configures the query.
 * @return An [IPagedQuery] object representing the configured paged query.
 */
fun pagedQuery(block: PagedQueryDsl.() -> Unit): IPagedQuery {
    val dsl = PagedQueryDsl()
    dsl.block()
    return dsl.build()
}

/**
 * Creates a [Condition] based on the provided DSL block.
 *
 * @param block The DSL block to define the condition.
 * @return The constructed [Condition] object.
 */
fun condition(block: ConditionDsl.() -> Unit): Condition {
    val dsl = ConditionDsl()
    dsl.block()
    return dsl.build()
}

/**
 *
 */
fun projection(block: ProjectionDsl.() -> Unit): Projection {
    val dsl = ProjectionDsl()
    dsl.block()
    return dsl.build()
}

/**
 * Creates a [Pagination] object using the provided DSL block.
 *
 * @param block A lambda function that receives a [PaginationDsl] and configures it.
 * @return The configured [Pagination] object.
 */
fun pagination(block: PaginationDsl.() -> Unit): Pagination {
    val dsl = PaginationDsl()
    dsl.block()
    return dsl.build()
}

/**
 * Sorts a list of [Sort] objects based on the provided DSL block.
 *
 * This function creates an instance of [SortDsl], applies the provided DSL block to it, and then
 * builds and returns a list of [Sort] objects. The DSL allows for specifying fields and their
 * sort directions (ascending or descending).
 *
 * @param block A lambda with receiver of type [SortDsl] that defines the sorting criteria.
 * @return A list of [Sort] objects representing the specified sorting criteria.
 */
fun sort(block: SortDsl.() -> Unit): List<Sort> {
    val dsl = SortDsl()
    dsl.block()
    return dsl.build()
}
