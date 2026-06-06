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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class QueryableDslTest {

    private class TestQueryableDsl : QueryableDsl<ISingleQuery>() {
        fun exposedProjection(): Projection = projection
        fun exposedCondition(): Condition = condition
        fun exposedSort(): List<Sort> = sort

        override fun build(): ISingleQuery {
            return SingleQuery(condition, projection, sort)
        }
    }

    @Test
    fun `should use default values`() {
        val dsl = TestQueryableDsl()
        dsl.exposedProjection().assert().isEqualTo(Projection.ALL)
        dsl.exposedCondition().assert().isEqualTo(Condition.all())
        dsl.exposedSort().assert().isEmpty()
    }

    @Test
    fun `should set projection directly`() {
        val dsl = TestQueryableDsl()
        val projection = Projection(include = listOf("field1"), exclude = emptyList())
        dsl.projection(projection)
        dsl.exposedProjection().assert().isEqualTo(projection)
    }

    @Test
    fun `should set projection via block`() {
        val dsl = TestQueryableDsl()
        dsl.projection {
            include("field1")
        }
        dsl.exposedProjection().include.assert().hasSize(1)
    }

    @Test
    fun `should set condition directly`() {
        val dsl = TestQueryableDsl()
        val condition = Condition.eq("field1", "value1")
        dsl.condition(condition)
        dsl.exposedCondition().assert().isEqualTo(condition)
    }

    @Test
    fun `should set condition via block`() {
        val dsl = TestQueryableDsl()
        dsl.condition {
            "field1" eq "value1"
        }
        dsl.exposedCondition().assert().isEqualTo(Condition.eq("field1", "value1"))
    }

    @Test
    fun `should set sort directly`() {
        val dsl = TestQueryableDsl()
        val sorts = listOf(Sort("field1", Sort.Direction.ASC))
        dsl.sort(sorts)
        dsl.exposedSort().assert().isEqualTo(sorts)
    }

    @Test
    fun `should set sort via block`() {
        val dsl = TestQueryableDsl()
        dsl.sort {
            "field1".asc()
        }
        dsl.exposedSort().assert().isEqualTo(listOf(Sort("field1", Sort.Direction.ASC)))
    }

    @Test
    fun `should build with all components`() {
        val query = TestQueryableDsl().apply {
            condition { "field1" eq "value1" }
            projection { include("field1") }
            sort { "field1".asc() }
        }.build()
        query.condition.assert().isEqualTo(Condition.eq("field1", "value1"))
        query.projection.include.assert().hasSize(1)
        query.sort.assert().hasSize(1)
    }
}
