package me.ahoo.wow.query

import me.ahoo.wow.api.query.Condition
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class ConditionDslTest {

    @Suppress("LongMethod")
    @Test
    fun test() {
        val condition = condition {
            not {
                all()
            }
            id("id")
            ids("id", "id2")
            "field1" eq "value1"
            "field2" ne "value2"
            "filed3" gt 1
            "field4" lt 1
            "field5" gte 1
            "field6" lte 1
            "field7" contains "value7"
            "field8" isIn listOf("value8")
            "field9" notIn listOf("value9")
            "field10" between (1 to 2)
            "field11" all listOf("value11")
            "field12" startsWith "value12"
            "field12" endsWith "value12"
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
            raw("1=1")
        }
        assertThat(
            condition,
            equalTo(
                Condition.and(
                    listOf(
                        Condition.all().not(),
                        Condition.id("id"),
                        Condition.ids("id", "id2"),
                        Condition.eq("field1", "value1"),
                        Condition.ne("field2", "value2"),
                        Condition.gt("filed3", 1),
                        Condition.lt("field4", 1),
                        Condition.gte("field5", 1),
                        Condition.lte("field6", 1),
                        Condition.contains("field7", "value7"),
                        Condition.isIn("field8", listOf("value8")),
                        Condition.notIn("field9", listOf("value9")),
                        Condition.between("field10", 1, 2),
                        Condition.all("field11", listOf("value11")),
                        Condition.startsWith("field12", "value12"),
                        Condition.endsWith("field12", "value12"),
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
                        Condition.raw("1=1")
                    )
                )
            )
        )
    }

    @Test
    fun and() {
        val condition = condition {
            and {
                "field3" eq "value3"
                "field4" eq "value4"
            }
        }
        assertThat(
            condition,
            equalTo(
                Condition.and(
                    listOf(
                        Condition.eq("field3", "value3"),
                        Condition.eq("field4", "value4")
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
        assertThat(condition, equalTo(Condition.all()))
    }

    @Test
    fun or() {
        val condition = condition {
            or {
                "field3" eq "value3"
                "field4" eq "value4"
            }
        }
        assertThat(
            condition,
            equalTo(
                Condition.or(
                    listOf(
                        Condition.eq("field3", "value3"),
                        Condition.eq("field4", "value4")
                    )
                )
            )
        )
    }

    @Test
    fun orEmpty() {
        val condition = condition {
            or {
            }
        }
        assertThat(condition, equalTo(Condition.all()))
    }

    @Test
    fun empty() {
        val condition = condition {
            all()
        }
        assertThat(condition, equalTo(Condition.all()))
    }

    @Test
    fun eq() {
        val condition = condition {
            "field1" eq "value1"
        }
        assertThat(condition, equalTo(Condition.eq("field1", "value1")))
    }

    @Test
    fun ne() {
        val condition = condition {
            "field1" ne "value1"
        }
        assertThat(condition, equalTo(Condition.ne("field1", "value1")))
    }

    @Test
    fun gt() {
        val condition = condition {
            "field1" gt 1
        }
        assertThat(condition, equalTo(Condition.gt("field1", 1)))
    }

    @Test
    fun lt() {
        val condition = condition {
            "field1" lt 1
        }
        assertThat(condition, equalTo(Condition.lt("field1", 1)))
    }

    @Test
    fun gte() {
        val condition = condition {
            "field1" gte 1
        }
        assertThat(condition, equalTo(Condition.gte("field1", 1)))
    }

    @Test
    fun lte() {
        val condition = condition {
            "field1" lte 1
        }
        assertThat(condition, equalTo(Condition.lte("field1", 1)))
    }

    @Test
    fun like() {
        val condition = condition {
            "field1" contains "value1"
        }
        assertThat(condition, equalTo(Condition.contains("field1", "value1")))
    }

    @Test
    fun isIn() {
        val condition = condition {
            "field1" isIn listOf("value1")
        }
        assertThat(condition, equalTo(Condition.isIn("field1", listOf("value1"))))
    }

    @Test
    fun notIn() {
        val condition = condition {
            "field1" notIn listOf("value1")
        }
        assertThat(condition, equalTo(Condition.notIn("field1", listOf("value1"))))
    }

    @Test
    fun between() {
        val condition = condition {
            "field1" between (1 to 2)
        }
        assertThat(condition, equalTo(Condition.between("field1", 1, 2)))
    }

    @Test
    fun all() {
        val condition = condition {
            "field1" all listOf("value1")
        }
        assertThat(condition, equalTo(Condition.all("field1", listOf("value1"))))
    }

    @Test
    fun startsWith() {
        val condition = condition {
            "field1" startsWith "value1"
        }
        assertThat(condition, equalTo(Condition.startsWith("field1", "value1")))
    }

    @Test
    fun elemMatch() {
        val condition = condition {
            "field1" elemMatch {
                "field2" eq "value2"
            }
        }
        assertThat(
            condition,
            equalTo(
                Condition.elemMatch(
                    "field1",
                    Condition.eq("field2", "value2")
                )
            )
        )
    }

    @Test
    fun isNull() {
        val condition = condition {
            "field1".isNull()
        }
        assertThat(condition, equalTo(Condition.isNull("field1")))
    }

    @Test
    fun notNull() {
        val condition = condition {
            "field1".notNull()
        }
        assertThat(condition, equalTo(Condition.notNull("field1")))
    }

    @ParameterizedTest
    @MethodSource("buildParameters")
    fun build(condition: Condition, expected: Condition) {
        assertThat(condition, equalTo(expected))
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
