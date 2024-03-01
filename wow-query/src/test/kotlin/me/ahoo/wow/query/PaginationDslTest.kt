package me.ahoo.wow.query

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class PaginationDslTest {

    @Test
    fun build() {
        val pagination = pagination {
            index(1)
            size(1)
        }

        assertThat(pagination.index, equalTo(1))
        assertThat(pagination.size, equalTo(1))
    }
}
