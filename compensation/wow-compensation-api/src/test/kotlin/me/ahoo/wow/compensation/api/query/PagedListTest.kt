package me.ahoo.wow.compensation.api.query

import me.ahoo.wow.api.query.PagedList
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class PagedListTest {

    @Test
    fun ctor() {
        val pagedList = PagedList(1, listOf(1))
        assertThat(pagedList.total, equalTo(1L))
        assertThat(pagedList.list, equalTo(listOf(1)))
    }
}
