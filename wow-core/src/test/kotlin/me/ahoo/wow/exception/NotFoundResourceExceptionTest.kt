package me.ahoo.wow.exception

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class NotFoundResourceExceptionTest {
    @Test
    fun monoThrowNotFoundIfEmpty() {
        Mono.empty<String>()
            .throwNotFoundIfEmpty("Not found.", null)
            .test()
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }

    @Test
    fun fluxThrowNotFoundIfEmpty() {
        Flux.empty<String>()
            .throwNotFoundIfEmpty("not found", RuntimeException())
            .test()
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }

    @Test
    fun throwNotFoundIfNull() {
        assertThrownBy<NotFoundResourceException> {
            null.throwNotFoundIfNull("null value")
        }
    }

    @Test
    fun ifNotNull() {
        "".throwNotFoundIfNull()
    }
}
