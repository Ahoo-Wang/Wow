package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class PagedQueryTest {
    @Test
    fun `should count a bounded total by default`() {
        PagedQuery(MatchAllFilter).total.assert().isEqualTo(TotalMode.BOUNDED)
    }

    @Test
    fun `should preserve the total mode while rewriting filter and projection`() {
        val query = PagedQuery(MatchAllFilter, total = TotalMode.NONE)
            .withFilter(IdFilter("id"))
            .withProjection(Projection(include = listOf(QueryField("state.name"))))

        query.filter.assert().isEqualTo(IdFilter("id"))
        query.total.assert().isEqualTo(TotalMode.NONE)
    }
}
