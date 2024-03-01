package me.ahoo.wow.query

import me.ahoo.wow.api.query.Sort
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SortDslTest {

    @Test
    fun asc() {
        val sort = sort {
            "field1".asc()
        }
        assertThat(sort, equalTo(listOf(Sort("field1", Sort.Direction.ASC))))
    }

    @Test
    fun desc() {
        val sort = sort {
            "field1".desc()
        }
        assertThat(sort, equalTo(listOf(Sort("field1", Sort.Direction.DESC))))
    }
}
