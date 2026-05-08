package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class PagedListTest {
    @Test
    fun `should return empty paged list`() {
        PagedList.empty<String>().assert().isSameAs(PagedList.empty<String>())
    }
}
