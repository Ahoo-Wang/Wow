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

interface IListQuery : Queryable<IListQuery> {
    @get:Schema(defaultValue = "10")
    val limit: Int
}

data class ListQuery(
    override val condition: Condition,
    override val projection: Projection = Projection.ALL,
    @field:JsonInclude(JsonInclude.Include.NON_EMPTY)
    override val sort: List<Sort> = emptyList(),
    override val limit: Int = Pagination.DEFAULT.size
) : IListQuery {
    override fun withCondition(newCondition: Condition): IListQuery {
        return copy(condition = newCondition)
    }

    override fun withProjection(newProjection: Projection): IListQuery {
        return copy(projection = newProjection)
    }
}
