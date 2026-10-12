package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PagedListTest {
    @Test
    fun `should return empty paged list`() {
        PagedList.empty<String>().assert().isSameAs(PagedList.empty<String>())
        PagedList.empty<String>().total.assert().isEqualTo(0L)
        PagedList.empty<String>().exact.assert().isTrue()
    }

    @Test
    fun `should default exact to whether the total was counted`() {
        PagedList(150, listOf("a")).exact.assert().isTrue()
        PagedList(null, listOf("a")).let {
            it.total.assert().isNull()
            it.exact.assert().isFalse()
        }
    }

    @Test
    fun `should keep a bounded total as a lower bound`() {
        val page = PagedList(10_000, listOf("a"), exact = false)

        page.total.assert().isEqualTo(10_000L)
        page.exact.assert().isFalse()
    }

    @Test
    fun `should reject a negative total and an exact uncounted total`() {
        assertThrows<IllegalArgumentException> { PagedList(-1, emptyList<String>()) }
        assertThrows<IllegalArgumentException> { PagedList(null, emptyList<String>(), exact = true) }
    }
}
