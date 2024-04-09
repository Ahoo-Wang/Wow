package me.ahoo.wow.query

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class SnapshotStatesKtTest {

    @Test
    fun toState() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "state"
        }
        snapshot.toMono().toState().test().expectNext("state").verifyComplete()
    }

    @Test
    fun fluxToState() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "state"
        }
        Flux.just(snapshot).toState().test().expectNext("state").verifyComplete()
    }

    @Test
    fun toStatePagedList() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "state"
        }
        val pagedList = PagedList(1, listOf(snapshot))
        Mono.just(pagedList).toStatePagedList().test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
