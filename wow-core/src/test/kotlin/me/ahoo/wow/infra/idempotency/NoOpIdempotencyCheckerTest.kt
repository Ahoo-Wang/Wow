package me.ahoo.wow.infra.idempotency

import me.ahoo.wow.id.GlobalIdGenerator
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class NoOpIdempotencyCheckerTest {

    @Test
    fun check() {
        val requestId = GlobalIdGenerator.generateAsString()
        NoOpIdempotencyChecker.check(requestId)
            .test()
            .expectNext(true)
            .verifyComplete()
        NoOpIdempotencyChecker.check(requestId)
            .test()
            .expectNext(true)
            .verifyComplete()
    }
}
