package me.ahoo.wow.exception

import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class NotFoundResourceExceptionTest {
    @Test
    fun monoThrowNotFoundIfEmpty() {
        Mono.empty<String>()
            .throwNotFoundIfEmpty("Not found.")
            .test()
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }

    @Test
    fun fluxThrowNotFoundIfEmpty() {
        Flux.empty<String>()
            .throwNotFoundIfEmpty()
            .test()
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }
}
