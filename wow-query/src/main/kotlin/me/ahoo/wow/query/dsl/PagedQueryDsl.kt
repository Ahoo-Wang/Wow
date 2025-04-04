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
 * ```kotlin
 * pagedQuery {
 *     pagination {
 *         index(1)
 *         size(10)
 *     }
 *     sort {
 *         "field1".asc()
 *     }
 *     condition {
 *         "field1" eq "value1"
 *         "field2" ne "value2"
 *         "filed3" gt 1
 *         "field4" lt 1
 *         "field5" gte 1
 *         "field6" lte 1
 *         "field7" like "value7"
 *         "field8" isIn listOf("value8")
 *         "field9" notIn listOf("value9")
 *         "field10" between (1 to 2)
 *         "field11" all listOf("value11")
 *         "field12" startsWith "value12"
 *         "field13" elemMatch {
 *             "field14" eq "value14"
 *         }
 *         "field15".isNull()
 *         "field16".notNull()
 *         and {
 *             "field3" eq "value3"
 *             "field4" eq "value4"
 *         }
 *         or {
 *             "field3" eq "value3"
 *             "field4" eq "value4"
 *         }
 *     }
 * }
 * ```
 */
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
