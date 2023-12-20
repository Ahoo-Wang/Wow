package me.ahoo.wow.compensation.api.query

import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_PAGE_INDEX
import me.ahoo.wow.compensation.api.query.IPagedQuery.Companion.DEFAULT_PAGE_SIZE
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class PagedQueryTest {

    @Test
    fun default() {
        val pagedQuery = PagedQuery()
        assertThat(pagedQuery.sort, equalTo(IPagedQuery.DEFAULT_SORT))
        assertThat(pagedQuery.pageIndex, equalTo(DEFAULT_PAGE_INDEX))
        assertThat(pagedQuery.pageSize, equalTo(DEFAULT_PAGE_SIZE))
        assertThat(pagedQuery.offset(), equalTo(0))
    }

    @Test
    fun customize() {
        val pagedQuery = PagedQuery(IPagedQuery.DEFAULT_SORT, 2, 10)
        assertThat(pagedQuery.sort, equalTo(IPagedQuery.DEFAULT_SORT))
        assertThat(pagedQuery.pageIndex, equalTo(2))
        assertThat(pagedQuery.pageSize, equalTo(10))
        assertThat(pagedQuery.offset(), equalTo(10))
    }
}
