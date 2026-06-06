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
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.junit.jupiter.api.Test

class ListQueryDslTest {

    @Test
    fun `should build list query with all components`() {
        val query = listQuery {
            limit(1)
            sort {
                "field1".asc()
            }
            condition {
                "field1" eq "value1"
                "field2" eq "value2"
                and {
                    "field3" eq "value3"
                }
                or {
                    "field4" eq "value4"
                }
            }
            projection { }
        }
        query.projection.assert().isEqualTo(Projection.ALL)
        query.limit.assert().isOne()
        query.sort.assert().isEqualTo(listOf(Sort("field1", Sort.Direction.ASC)))
        query.condition.assert().isEqualTo(
            Condition.and(
                listOf(
                    Condition.eq("field1", "value1"),
                    Condition.eq("field2", "value2"),
                    Condition.and(
                        listOf(
                            Condition.eq("field3", "value3")
                        )
                    ),
                    Condition.or(
                        listOf(
                            Condition.eq("field4", "value4")
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `should build empty list query with defaults`() {
        val query = listQuery { }
        query.condition.assert().isEqualTo(Condition.all())
        query.projection.assert().isEqualTo(Projection.ALL)
        query.sort.assert().isEmpty()
        query.limit.assert().isZero()
    }

    @Test
    fun `should build list query with only limit`() {
        val query = listQuery {
            limit(10)
        }
        query.limit.assert().isEqualTo(10)
        query.condition.assert().isEqualTo(Condition.all())
    }

    @Test
    fun `should build list query with projection block`() {
        val query = listQuery {
            projection {
                include("field1")
                exclude("field2")
            }
        }
        query.projection.include.assert().hasSize(1)
        query.projection.exclude.assert().hasSize(1)
    }
}
