package me.ahoo.wow.api.query

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CountQueryTest {
    @Test
    fun `should default the limit to the budget`() {
        CountQuery(MatchAllFilter).limit.assert().isNull()
    }

    @Test
    fun `should accept a positive limit and reject a limit below one`() {
        CountQuery(MatchAllFilter, limit = 1).limit.assert().isEqualTo(1)
        assertThrows<IllegalArgumentException> { CountQuery(MatchAllFilter, limit = 0) }
        assertThrows<IllegalArgumentException> { CountQuery(MatchAllFilter, limit = -1) }
    }

    @Test
    fun `should preserve the limit while rewriting the filter`() {
        val query = CountQuery(MatchAllFilter, limit = 1000)
            .withFilter(IdFilter("id"))
            .appendFilter(TenantIdFilter("tenant"))

        query.filter.assert().isEqualTo(IdFilter("id").appendFilter(TenantIdFilter("tenant")))
        query.limit.assert().isEqualTo(1000)
    }

    @Test
    fun `should carry a count and whether it is exact`() {
        CountResult(42, exact = true).let {
            it.count.assert().isEqualTo(42L)
            it.exact.assert().isTrue()
        }
        CountResult(1000, exact = false).exact.assert().isFalse()
        assertThrows<IllegalArgumentException> { CountResult(-1, exact = true) }
    }
}
