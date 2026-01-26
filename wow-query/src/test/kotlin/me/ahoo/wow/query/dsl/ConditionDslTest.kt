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
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.Operator
import me.ahoo.wow.query.snapshot.nestedState
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalTime
import java.util.stream.Stream

class ConditionDslTest {

    @Suppress("LongMethod")
    @Test
    fun test() {
        val condition = condition {
            deleted(false)
            deleted(DeletionState.ACTIVE)
            and {
                tenantId("tenantId")
            }
            nor {
                all()
            }
            id("id")
            ids("id", "id2")
            aggregateId("id")
            aggregateIds("id", "id2")
            "field1" eq "value1"
            "field2" ne "value2"
            "filed3" gt 1
            "field4" lt 1
            "field5" gte 1
            "field6" lte 1
            "field7" contains "value7"
            "field7".contains("value7", false)
            "field7".contains("value7", true)
            "field8" isIn listOf("value8")
            "field9" notIn listOf("value9")
            "field10" between (1 to 2)
            "field100" between 1 to 2
            "field11" all listOf("value11")
            "field12" startsWith "value12"
            "field12".startsWith("value12", false)
            "field12".startsWith("value12", true)
            "field12" endsWith "value12"
            "field12".endsWith("value12", false)
            "field12".endsWith("value12", true)
            "field13" elemMatch {
                "field14" eq "value14"
            }
            "field15".isNull()
            "field16".notNull()
            "field17".isTrue()
            "field18".isFalse()
            and {
                "field3" eq "value3"
                "field4" eq "value4"
            }
            or {
                "field3" eq "value3"
                "field4" eq "value4"
            }
            "field19".today()
            "field20" beforeToday LocalTime.of(17, 0)
            "field20".tomorrow()
            "field21".thisWeek()
            "field22".nextWeek()
            "field23".lastWeek()
            "field24".thisMonth()
            "field25".lastMonth()
            "field26".recentDays(1)
            raw("1=1")
            "state" nested {
                "field27" eq "value27"
                "field28" eq "value28"
                "child" nested {
                    "field29" eq "value29"
                }
                nested("")
                "field30" eq "value30"
            }
        }
        condition.assert().isEqualTo(
            Condition.and(
                listOf(
                    Condition.deleted(false),
                    Condition.deleted(DeletionState.ACTIVE),
                    Condition.and(Condition.tenantId("tenantId")),
                    Condition.nor(Condition.all()),
                    Condition.id("id"),
                    Condition.ids("id", "id2"),
                    Condition.aggregateId("id"),
                    Condition.aggregateIds("id", "id2"),
                    Condition.eq("field1", "value1"),
                    Condition.ne("field2", "value2"),
                    Condition.gt("filed3", 1),
                    Condition.lt("field4", 1),
                    Condition.gte("field5", 1),
                    Condition.lte("field6", 1),
                    Condition.contains("field7", "value7"),
                    Condition.contains("field7", "value7", false),
                    Condition.contains("field7", "value7", true),
                    Condition.isIn("field8", listOf("value8")),
                    Condition.notIn("field9", listOf("value9")),
                    Condition.between("field10", 1, 2),
                    Condition.between("field100", 1, 2),
                    Condition.all("field11", listOf("value11")),
                    Condition.startsWith("field12", "value12"),
                    Condition.startsWith("field12", "value12", false),
                    Condition.startsWith("field12", "value12", true),
                    Condition.endsWith("field12", "value12"),
                    Condition.endsWith("field12", "value12", false),
                    Condition.endsWith("field12", "value12", true),
                    Condition.elemMatch("field13", Condition.eq("field14", "value14")),
                    Condition.isNull("field15"),
                    Condition.notNull("field16"),
                    Condition.isTrue("field17"),
                    Condition.isFalse("field18"),
                    Condition.and(
                        listOf(
                            Condition.eq("field3", "value3"),
                            Condition.eq("field4", "value4")
                        )
                    ),
                    Condition.or(
                        listOf(
                            Condition.eq("field3", "value3"),
                            Condition.eq("field4", "value4")
                        )
                    ),
                    Condition.today("field19"),
                    Condition.beforeToday("field20", LocalTime.of(17, 0)),
                    Condition.tomorrow("field20"),
                    Condition.thisWeek("field21"),
                    Condition.nextWeek("field22"),
                    Condition.lastWeek("field23"),
                    Condition.thisMonth("field24"),
                    Condition.lastMonth("field25"),
                    Condition.recentDays("field26", 1),
                    Condition.raw("1=1"),
                    Condition.eq("state.field27", "value27"),
                    Condition.eq("state.field28", "value28"),
                    Condition.eq("state.child.field29", "value29"),
                    Condition.eq("field30", "value30"),
                )
            )
        )
    }

    @Test
    fun nested() {
        val condition = condition {
            QueryModel::id nested {
                QueryModel::id eq "value"
            }
        }
        condition.assert().isEqualTo(Condition.eq("id.id", "value"))
    }

    @Test
    fun and() {
        val condition = condition {
            nested("state")
            and {
                "field3" eq "value3"
                "field4" eq "value4"
                and {
                    "field5" eq "value5"
                    nested("")
                    "field6" eq "value6"
                }
            }
        }

        condition.assert().isEqualTo(
            Condition.and(
                listOf(
                    Condition.eq("state.field3", "value3"),
                    Condition.eq("state.field4", "value4"),
                    Condition.and(
                        Condition.eq("state.field5", "value5"),
                        Condition.eq("field6", "value6")
                    )
                )
            )
        )
    }

    @Test
    fun andEmpty() {
        val condition = condition {
            and {
            }
        }
        condition.assert().isEqualTo(Condition.all())
    }

    @Test
    fun or() {
        val condition = condition {
            nestedState()
            or {
                "field3" eq "value3"
                "field4" eq "value4"
            }
        }
        condition.assert().isEqualTo(
            Condition.or(
                listOf(
                    Condition.eq("state.field3", "value3"),
                    Condition.eq("state.field4", "value4")
                )
            )
        )
    }

    @Test
    fun nor() {
        val condition = condition {
            nor {
            }
        }
        condition.assert().isEqualTo(Condition.ALL)
    }

    @Test
    fun orEmpty() {
        val condition = condition {
            or {
            }
        }
        condition.assert().isEqualTo(Condition.ALL)
    }

    @Test
    fun empty() {
        val condition = condition {
            all()
        }
        condition.assert().isEqualTo(Condition.ALL)
    }

    @Test
    fun deleted() {
        val condition = condition {
            deleted(DeletionState.DELETED)
        }
        condition.deletionState().assert().isEqualTo(DeletionState.DELETED)
    }

    @Test
    fun deletedWithBool() {
        val condition = condition {
            deleted(true)
        }
        condition.deletionState().assert().isEqualTo(DeletionState.DELETED)
    }

    @Test
    fun deletedWithString() {
        val condition = Condition(operator = Operator.DELETED, value = "DELETED")
        condition.deletionState().assert().isEqualTo(DeletionState.DELETED)
    }

    @Test
    fun eq() {
        val condition = condition {
            QueryModel::id eq "value"
        }
        condition.assert().isEqualTo(Condition.eq("id", "value"))
    }

    @Test
    fun ne() {
        val condition = condition {
            QueryModel::id ne "value"
        }
        condition.assert().isEqualTo(Condition.ne("id", "value"))
    }

    @Test
    fun gt() {
        val condition = condition {
            QueryModel::id gt 1
        }
        condition.assert().isEqualTo(Condition.gt("id", 1))
    }

    @Test
    fun lt() {
        val condition = condition {
            QueryModel::id lt 1
        }
        condition.assert().isEqualTo(Condition.lt("id", 1))
    }

    @Test
    fun gte() {
        val condition = condition {
            QueryModel::id gte 1
        }
        condition.assert().isEqualTo(Condition.gte("id", 1))
    }

    @Test
    fun lte() {
        val condition = condition {
            QueryModel::id lte 1
        }
        condition.assert().isEqualTo(Condition.lte("id", 1))
    }

    @Test
    fun like() {
        val condition = condition {
            QueryModel::id contains "value1"
        }
        condition.assert().isEqualTo(Condition.contains("id", "value1"))
    }

    @Test
    fun isIn() {
        val condition = condition {
            QueryModel::id isIn listOf("value1")
        }
        condition.assert().isEqualTo(Condition.isIn("id", listOf("value1")))
    }

    @Test
    fun notIn() {
        val condition = condition {
            QueryModel::id notIn listOf("value1")
        }
        condition.assert().isEqualTo(Condition.notIn("id", listOf("value1")))
    }

    @Test
    fun between() {
        val condition = condition {
            QueryModel::id between 1 to 2
        }
        condition.assert().isEqualTo(Condition.between("id", 1, 2))
    }

    @Test
    fun all() {
        val condition = condition {
            QueryModel::id all listOf("value1")
        }
        condition.assert().isEqualTo(Condition.all("id", listOf("value1")))
    }

    @Test
    fun contains() {
        val condition = condition {
            QueryModel::id contains "value1"
        }
        condition.assert().isEqualTo(Condition.contains("id", "value1"))
    }

    @Test
    fun startsWith() {
        val condition = condition {
            QueryModel::id startsWith "value1"
        }
        condition.assert().isEqualTo(Condition.startsWith("id", "value1"))
    }

    @Test
    fun endsWith() {
        val condition = condition {
            QueryModel::id endsWith "value1"
        }
        condition.assert().isEqualTo(Condition.endsWith("id", "value1"))
    }

    @Test
    fun elemMatch() {
        val condition = condition {
            QueryModel::id elemMatch {
                "field2" eq "value2"
            }
        }
        condition.assert().isEqualTo(
            Condition.elemMatch(
                "id",
                Condition.eq("field2", "value2")
            )
        )
    }

    @Test
    fun isNull() {
        val condition = condition {
            QueryModel::id.isNull()
        }
        condition.assert().isEqualTo(Condition.isNull("id"))
    }

    @Test
    fun notNull() {
        val condition = condition {
            QueryModel::id.notNull()
        }
        condition.assert().isEqualTo(Condition.notNull("id"))
    }

    @Test
    fun isTrue() {
        val condition = condition {
            QueryModel::id.isTrue()
        }
        condition.assert().isEqualTo(Condition.isTrue("id"))
    }

    @Test
    fun isFalse() {
        val condition = condition {
            QueryModel::id.isFalse()
        }
        condition.assert().isEqualTo(Condition.isFalse("id"))
    }

    @Test
    fun exists() {
        val condition = condition {
            QueryModel::id.exists()
        }
        condition.assert().isEqualTo(Condition.exists("id"))
    }

    @Test
    fun notExists() {
        val condition = condition {
            QueryModel::id.exists(false)
        }
        condition.assert().isEqualTo(Condition.exists("id", false))
    }

    @Test
    fun tenantId() {
        val condition = condition {
            tenantId("tenantId")
        }
        condition.assert().isEqualTo(Condition.tenantId("tenantId"))
    }

    @Test
    fun ownerId() {
        val condition = condition {
            ownerId("ownerId")
        }
        condition.assert().isEqualTo(Condition.ownerId("ownerId"))
    }

    @Test
    fun spaceId() {
        val condition = condition {
            spaceId("spaceId")
        }
        condition.assert().isEqualTo(Condition.spaceId("spaceId"))
    }

    @Test
    fun today() {
        val condition = condition {
            QueryModel::id.today("yyyy-MM-dd")
        }
        condition.assert().isEqualTo(Condition.today("id", "yyyy-MM-dd"))
    }

    @Test
    fun tomorrow() {
        val condition = condition {
            QueryModel::id.tomorrow()
        }
        condition.assert().isEqualTo(Condition.tomorrow("id"))
    }

    @Test
    fun thisWeek() {
        val condition = condition {
            QueryModel::id.thisWeek()
        }
        condition.assert().isEqualTo(Condition.thisWeek("id"))
    }

    @Test
    fun nextWeek() {
        val condition = condition {
            QueryModel::id.nextWeek()
        }
        condition.assert().isEqualTo(Condition.nextWeek("id"))
    }

    @Test
    fun lastWeek() {
        val condition = condition {
            QueryModel::id.lastWeek()
        }
        condition.assert().isEqualTo(Condition.lastWeek("id"))
    }

    @Test
    fun thisMonth() {
        val condition = condition {
            QueryModel::id.thisMonth()
        }
        condition.assert().isEqualTo(Condition.thisMonth("id"))
    }

    @Test
    fun lastMonth() {
        val condition = condition {
            QueryModel::id.lastMonth()
        }
        condition.assert().isEqualTo(Condition.lastMonth("id"))
    }

    @Test
    fun recentDays() {
        val condition = condition {
            QueryModel::id recentDays 1
        }
        condition.assert().isEqualTo(Condition.recentDays("id", 1))
    }

    @Test
    fun earlierDays() {
        val condition = condition {
            QueryModel::id.name.earlierDays(1)
        }
        condition.assert().isEqualTo(Condition.earlierDays("id", 1))
    }

    @ParameterizedTest
    @MethodSource("buildParameters")
    fun build(condition: Condition, expected: Condition) {
        condition.assert().isEqualTo(expected)
    }

    companion object {
        @JvmStatic
        fun buildParameters(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    condition {
                    },
                    Condition.all()
                ),
                Arguments.of(
                    condition {
                        all()
                    },
                    Condition.all()
                ),
                Arguments.of(
                    condition {
                        all()
                        all()
                    },
                    Condition.and(listOf(Condition.all(), Condition.all()))
                ),
            )
        }
    }
}

data class QueryModel(val id: String, val name: String)
