package me.ahoo.wow.query.filter

import me.ahoo.wow.filter.FilterChainBuilder
import me.ahoo.wow.filter.LogErrorHandler
import me.ahoo.wow.query.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.condition
import me.ahoo.wow.query.singleQuery
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class DefaultSnapshotQueryHandlerTest {
    private val tailSnapshotQueryFilter = TailSnapshotQueryFilter<Any>(NoOpSnapshotQueryServiceFactory)
    private val snapshotQueryFilterChain = FilterChainBuilder<SnapshotQueryContext<*, *, *>>()
        .addFilters(listOf(tailSnapshotQueryFilter))
        .filterCondition(SnapshotQueryHandler::class)
        .build()
    private val queryHandler = DefaultSnapshotQueryHandler(
        snapshotQueryFilterChain,
        LogErrorHandler()
    )

    @Test
    fun single() {
        val query = singleQuery {
        }

        queryHandler.single<Any>(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun query() {
        val query = me.ahoo.wow.query.listQuery { }
        queryHandler.query<Any>(MOCK_AGGREGATE_METADATA, query)
            .test().verifyComplete()
    }

    @Test
    fun pagedQuery() {
        val pagedQuery = me.ahoo.wow.query.pagedQuery { }
        queryHandler.pagedQuery<Any>(MOCK_AGGREGATE_METADATA, pagedQuery)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        val condition = condition {
            id("1")
        }
        queryHandler.count(MOCK_AGGREGATE_METADATA, condition)
            .test()
            .consumeNextWith {
                assertThat(it, equalTo(0))
            }
            .verifyComplete()
    }
}
