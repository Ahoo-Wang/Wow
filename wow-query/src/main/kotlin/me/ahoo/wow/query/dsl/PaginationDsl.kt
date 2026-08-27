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

import me.ahoo.wow.api.query.Pagination

/**
 * A DSL for constructing [Pagination] objects.
 *
 * This class provides a fluent API to configure and build [Pagination] instances, allowing
 * the user to specify the index and size of the pagination. The `index` represents the current
 * page number, and the `size` represents the number of items per page.
 *
 * Example usage:
 * ```kotlin
 * val pagination = pagination {
 *     index(2)
 *     size(15)
 * }
 * ```
 * In this example, a [Pagination] object is created with an index (page number) of 2 and a size (items per page) of 15.
 *
 * @see Pagination
 */
@QueryDslMarker
class PaginationDsl {

    private var index = Pagination.DEFAULT.index
    private var size = Pagination.DEFAULT.size

    fun index(index: Int) {
        this.index = index
    }

    fun size(size: Int) {
        this.size = size
    }

    fun build(): Pagination {
        return Pagination(index, size)
    }
}
