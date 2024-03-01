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

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.api.query.Pagination
import me.ahoo.wow.api.query.Sort

class PagedQueryDsl {
    private var condition: Condition = Condition.empty()
    private var sort: List<Sort> = emptyList()
    private var pagination: Pagination = Pagination.DEFAULT_PAGINATION

    fun condition(block: ConditionDsl.() -> Unit) {
        val dsl = ConditionDsl()
        dsl.block()
        condition = dsl.build()
    }

    fun pagination(block: PaginationDsl.() -> Unit) {
        val dsl = PaginationDsl()
        dsl.block()
        pagination = dsl.build()
    }

    fun sort(block: SortDsl.() -> Unit) {
        val dsl = SortDsl()
        dsl.block()
        sort = dsl.build()
    }

    fun build(): PagedQuery {
        return PagedQuery(condition, sort, pagination)
    }

    companion object {
        fun pagedQuery(block: PagedQueryDsl.() -> Unit): PagedQuery {
            val dsl = PagedQueryDsl()
            dsl.block()
            return dsl.build()
        }
    }
}
