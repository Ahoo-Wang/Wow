package me.ahoo.wow.query

import me.ahoo.wow.id.GlobalIdGenerator
import me.ahoo.wow.modeling.toNamedAggregate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class NoOpSnapshotQueryServiceTest {
    val snapshotQueryService = NoOpSnapshotQueryServiceFactory.create("test.test".toNamedAggregate())

    @Test
    fun single() {
        val result = snapshotQueryService.single<Any>(
            GlobalIdGenerator.generateAsString(),
            Condition("test", Operator.EQ, "test")
        )
        assertThat(result, equalTo(Mono.empty()))
    }

    @Test
    fun query() {
        val result = snapshotQueryService.query<Any>(
            GlobalIdGenerator.generateAsString(),
            Query(Condition("test", Operator.EQ, "test"))
        )
        assertThat(result, equalTo(Flux.empty()))
    }

    @Test
    fun pagedQuery() {
        snapshotQueryService.pagedQuery<Any>(
            GlobalIdGenerator.generateAsString(),
            PagedQuery(Condition("test", Operator.EQ, "test"))
        ).test()
            .consumeNextWith {
                assertThat(it.total, equalTo(0))
            }
            .verifyComplete()
    }

    @Test
    fun count() {
        snapshotQueryService.count(
            GlobalIdGenerator.generateAsString(),
            Condition("test", Operator.EQ, "test")
        ).test()
            .expectNext(0L)
            .verifyComplete()
    }
}
