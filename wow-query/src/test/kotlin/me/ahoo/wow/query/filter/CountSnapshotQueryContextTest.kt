package me.ahoo.wow.query.filter

import me.ahoo.wow.query.condition
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test

class CountSnapshotQueryContextTest {

    @Test
    fun rewriteQuery() {
        val context = CountSnapshotQueryContext(MOCK_AGGREGATE_METADATA)
        val query = condition { }
        context.setQuery(query)
        context.rewriteQuery {
            it.appendTenantId("tenantId")
        }
        assertThat(
            context.getQuery(),
            equalTo(
                condition {
                    tenantId("tenantId")
                }
            )
        )
    }
}
