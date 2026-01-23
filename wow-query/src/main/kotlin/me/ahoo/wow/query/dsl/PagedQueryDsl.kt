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

import me.ahoo.wow.api.query.IPagedQuery
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination

/**
 * A DSL for constructing paged queries, extending the [QueryableDsl] to support pagination.
 *
 * This class allows you to define a query with specific conditions, projections, sorting, and pagination. It is
 * particularly useful when you need to fetch a subset of data from a larger dataset, applying page-based navigation.
 *
 * Example usage:
 * ```kotlin
 * val query = pagedQuery {
 *     condition {
 *         // Define your conditions here
 *     }
 *     projection {
 *         // Define your projections here
 *     }
 *     sort {
 *         "fieldName".asc()
 *     }
 *     pagination {
 *         index(2)
 *         size(10)
 *     }
 * }
 * ```
 *
 * @see QueryableDsl
 * @see IPagedQuery
 */
@QueryDslMarker
class PagedQueryDsl : QueryableDsl<IPagedQuery>() {
    private var pagination: Pagination = Pagination.DEFAULT

    fun pagination(pagination: Pagination) {
        this.pagination = pagination
    }

    fun pagination(block: PaginationDsl.() -> Unit) {
        val dsl = PaginationDsl()
        dsl.block()
        pagination(dsl.build())
    }

    override fun build(): IPagedQuery {
        return PagedQuery(condition, projection, sort, pagination)
    }
}
