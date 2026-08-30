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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Queryable
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.toFilterExpression

/**
 * Represents a DSL (Domain Specific Language) for constructing queryable objects. This abstract class allows
 * for the fluent and type-safe construction of queries, including setting up projections, conditions, and sorting.
 *
 *
 * @param Q The type of the queryable object that this DSL is constructing.
 */
@QueryDslMarker
abstract class QueryableDsl<Q : Queryable<Q>> {
    protected var projection: Projection = Projection.ALL
    protected var filter: FilterExpression = MatchAllFilter

    protected var sort: List<Sort> = emptyList()

    fun projection(projection: Projection) {
        this.projection = projection
    }

    fun projection(block: ProjectionDsl.() -> Unit) {
        val dsl = ProjectionDsl()
        dsl.block()
        projection(dsl.build())
    }

    fun filter(filter: FilterExpression) {
        this.filter = filter
    }

    fun filter(block: FilterDsl.() -> Unit) {
        filter(me.ahoo.wow.query.dsl.filter(block))
    }

    @Deprecated("Scheduled for removal in 9.1.0. Use filter.")
    fun condition(condition: Condition) {
        filter(condition.toFilterExpression())
    }

    @Deprecated("Scheduled for removal in 9.1.0. Use filter.")
    fun condition(block: ConditionDsl.() -> Unit) {
        condition(me.ahoo.wow.query.dsl.condition(block))
    }

    fun sort(sort: List<Sort>) {
        this.sort = sort
    }

    fun sort(block: SortDsl.() -> Unit) {
        val dsl = SortDsl()
        dsl.block()
        sort(dsl.build())
    }

    abstract fun build(): Q
}
