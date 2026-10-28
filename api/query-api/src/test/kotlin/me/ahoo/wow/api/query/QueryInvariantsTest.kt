package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The protocol's bounds hold when a query is built, the same for every query type, wherever it comes from. */
class QueryInvariantsTest {
    private val tooManySorts = (0..Sort.MAX_FIELDS).map { Sort(QueryField("f$it"), Sort.Direction.ASC) }

    @Test
    fun `a list names a limit of at least one, or none for the default`() {
        ListQuery(MatchAllFilter).limit.assert().isNull()
        ListQuery(MatchAllFilter, limit = 1).limit.assert().isEqualTo(1)
        assertThrows<IllegalArgumentException> { ListQuery(MatchAllFilter, limit = 0) }
            .message.assert().isEqualTo("limit must be at least 1.")
        assertThrows<IllegalArgumentException> { ListQuery(MatchAllFilter, limit = -1) }
    }

    @Test
    fun `a page has an index and a size of at least one`() {
        Pagination(index = 1, size = 1).offset().assert().isEqualTo(0L)
        assertThrows<IllegalArgumentException> { Pagination(index = 0, size = 10) }
            .message.assert().isEqualTo("index must be at least 1.")
        assertThrows<IllegalArgumentException> { Pagination(index = 1, size = 0) }
            .message.assert().isEqualTo("size must be at least 1.")
    }

    @Test
    fun `a deep page's offset passes the largest int`() {
        Pagination(index = Int.MAX_VALUE, size = 100).offset()
            .assert().isEqualTo((Int.MAX_VALUE - 1).toLong() * 100)
    }

    @Test
    fun `every query type bounds its sort fields`() {
        val message = "sort must contain at most ${Sort.MAX_FIELDS} fields."
        assertThrows<IllegalArgumentException> { SingleQuery(MatchAllFilter, sort = tooManySorts) }
            .message.assert().isEqualTo(message)
        assertThrows<IllegalArgumentException> { ListQuery(MatchAllFilter, sort = tooManySorts) }
            .message.assert().isEqualTo(message)
        assertThrows<IllegalArgumentException> { PagedQuery(MatchAllFilter, sort = tooManySorts) }
            .message.assert().isEqualTo(message)
        assertThrows<IllegalArgumentException> { CursorQuery(MatchAllFilter, sort = tooManySorts) }
            .message.assert().isEqualTo(message)
    }
}
