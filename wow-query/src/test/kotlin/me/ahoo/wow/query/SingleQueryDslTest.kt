package me.ahoo.wow.query

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.IProjectableQuery
import me.ahoo.wow.api.query.Projection
import me.ahoo.wow.api.query.Sort
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class SingleQueryDslTest {

    @Test
    fun query() {
        val query = singleQuery {
            sort {
                "field1".asc()
            }
            condition {
                "field1" eq "value1"
            }
        }

        assertThat(query.sort, equalTo(listOf(Sort("field1", Sort.Direction.ASC))))
        assertThat(
            query.condition,
            equalTo(Condition.eq("field1", "value1"))
        )
    }

    @Test
    fun projectionQuery() {
        val query = singleQuery {
            projection {
                include("field1")
            }
        } as IProjectableQuery

        assertThat(
            query.projection,
            equalTo(
                Projection(
                    include = listOf("field1")
                )
            )
        )
    }
}
