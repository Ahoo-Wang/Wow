package me.ahoo.wow.query

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IProjectableQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class PagedQueryDslTest {

    @Suppress("LongMethod")
    @Test
    fun build() {
        val pagedQuery = pagedQuery {
            pagination {
                index(1)
                size(10)
            }
            sort {
                "field1".asc()
            }
            condition {
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
                "field13" elemMatch {
                    "field14" eq "value14"
                }
                "field15".isNull()
                "field16".notNull()
                and {
                    "field3" eq "value3"
                    "field4" eq "value4"
                }
                or {
                    "field3" eq "value3"
                    "field4" eq "value4"
                }
            }
        }

        assertThat(pagedQuery.pagination.index, equalTo(1))
        assertThat(pagedQuery.pagination.size, equalTo(10))
        assertThat(pagedQuery.sort, equalTo(listOf(Sort("field1", Sort.Direction.ASC))))
        assertThat(
            pagedQuery.condition,
            equalTo(
                Condition.and(
                    listOf(
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
                        Condition.elemMatch("field13", Condition.eq("field14", "value14")),
                        Condition.isNull("field15"),
                        Condition.notNull("field16"),
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
                        )
                    )
                )
            )
        )
    }

    @Test
    fun projectionPagedQuery() {
        val query = pagedQuery {
            projection {
                include("field1")
                exclude("field2")
            }
        } as IProjectableQuery
        assertThat(
            query.projection,
            equalTo(
                Projection(
                    include = listOf("field1"),
                    exclude = listOf("field2")
                )
            )
        )
    }
}
