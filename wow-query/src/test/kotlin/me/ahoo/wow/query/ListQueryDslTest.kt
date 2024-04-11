package me.ahoo.wow.query

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IProjectableQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class ListQueryDslTest {

    @Test
    fun query() {
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
        }

        assertThat(query.limit, equalTo(1))
        assertThat(query.sort, equalTo(listOf(Sort("field1", Sort.Direction.ASC))))
        assertThat(
            query.condition,
            equalTo(
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
        )
    }

    @Test
    fun projectionQuery() {
        val query = listQuery {
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
