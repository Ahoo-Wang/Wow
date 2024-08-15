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

fun singleQuery(block: SingleQueryDsl.() -> Unit): ISingleQuery {
    val dsl = SingleQueryDsl()
    dsl.block()
    return dsl.build()
}

fun listQuery(block: ListQueryDsl.() -> Unit): IListQuery {
    val dsl = ListQueryDsl()
    dsl.block()
    return dsl.build()
}

fun pagedQuery(block: PagedQueryDsl.() -> Unit): IPagedQuery {
    val dsl = PagedQueryDsl()
    dsl.block()
    return dsl.build()
}

fun condition(block: ConditionDsl.() -> Unit): Condition {
    val dsl = ConditionDsl()
    dsl.block()
    return dsl.build()
}

fun projection(block: ProjectionDsl.() -> Unit): Projection {
    val dsl = ProjectionDsl()
    dsl.block()
    return dsl.build()
}

fun pagination(block: PaginationDsl.() -> Unit): Pagination {
    val dsl = PaginationDsl()
    dsl.block()
    return dsl.build()
}

fun sort(block: SortDsl.() -> Unit): List<Sort> {
    val dsl = SortDsl()
    dsl.block()
    return dsl.build()
}
