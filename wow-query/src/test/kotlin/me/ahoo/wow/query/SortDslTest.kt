package me.ahoo.wow.query

import me.ahoo.wow.api.query.Sort
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SortDslTest {

    @Test
    fun sort() {
        val sort = sort {
            "field1".asc()
            "field2".desc()
        }
        assertThat(
            sort,
            equalTo(
                listOf(
                    Sort("field1", Sort.Direction.ASC),
                    Sort("field2", Sort.Direction.DESC)
                )
            )
        )
    }
}
