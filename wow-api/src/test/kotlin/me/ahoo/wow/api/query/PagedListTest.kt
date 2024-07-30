package me.ahoo.wow.api.query

import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class PagedListTest {
    @Test
    fun emtpy() {
        assertThat(PagedList.empty(), sameInstance(PagedList.empty<String>()))
    }
}
