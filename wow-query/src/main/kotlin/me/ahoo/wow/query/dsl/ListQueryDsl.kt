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

import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.Pagination

/**
 * ```kotlin
 * listQuery {
 *     limit(1)
 *     sort {
 *         "field1".asc()
 *     }
 *     condition {
 *         "field1" eq "value1"
 *         "field2" eq "value2"
 *         and {
 *             "field3" eq "value3"
 *         }
 *         or {
 *             "field4" eq "value4"
 *         }
 *     }
 * }
 * ```
 */
class ListQueryDsl : QueryableDsl<IListQuery>() {
    private var limit: Int = Pagination.DEFAULT.size

    fun limit(limit: Int) {
        this.limit = limit
    }

    override fun build(): IListQuery {
        return ListQuery(condition, projection, sort, limit)
    }
}
