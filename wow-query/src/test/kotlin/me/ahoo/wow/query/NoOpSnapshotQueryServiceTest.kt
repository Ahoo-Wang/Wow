package me.ahoo.wow.query

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.toNamedAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpSnapshotQueryServiceTest {
    val snapshotQueryService = NoOpSnapshotQueryServiceFactory.create<Any>("test.test".toNamedAggregate())

    @Test
    fun single() {
        snapshotQueryService.single(GlobalIdGenerator.generateAsString()) {
            "test" eq "test"
        }
            .test()
            .verifyComplete()
    }

    @Test
    fun query() {
        snapshotQueryService.query(
            GlobalIdGenerator.generateAsString()
        ) {
            condition {
                "test" eq "test"
            }
        }
            .test()
            .verifyComplete()
    }

    @Test
    fun pagedQuery() {
        snapshotQueryService.pagedQuery(GlobalIdGenerator.generateAsString()) {
            condition {
                "test" eq "test"
            }
        }.toStatePagedList()
            .test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        snapshotQueryService.count(GlobalIdGenerator.generateAsString()) {
            "test" eq "test"
        }
            .test()
            .expectNext(0L)
            .verifyComplete()
    }
}
