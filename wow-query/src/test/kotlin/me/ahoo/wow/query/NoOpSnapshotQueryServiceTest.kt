package me.ahoo.wow.query

import me.ahoo.wow.modeling.toNamedAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpSnapshotQueryServiceTest {
    private val queryService = NoOpSnapshotQueryServiceFactory.create<Any>("test.test".toNamedAggregate())

    @Test
    fun aggregate() {
        assertThat(queryService.namedAggregate, equalTo("test.test".toNamedAggregate()))
    }

    @Test
    fun single() {
        condition {
            "test" eq "test"
        }.single(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun query() {
        query {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        pagedQuery {
            condition {
                "test" eq "test"
            }
        }.query(queryService)
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        condition {
            "test" eq "test"
        }.count(queryService)
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
