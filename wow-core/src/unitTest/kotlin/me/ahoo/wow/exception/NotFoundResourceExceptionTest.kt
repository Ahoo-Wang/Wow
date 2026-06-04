package me.ahoo.wow.exception

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class NotFoundResourceExceptionTest {
    @Test
    fun `should mono throw not found if empty`() {
        Mono.empty<String>()
            .throwNotFoundIfEmpty("Not found.", null)
            .test()
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }

    @Test
    fun `should flux throw not found if empty`() {
        Flux.empty<String>()
            .throwNotFoundIfEmpty("not found", RuntimeException())
            .test()
            .expectError(NotFoundResourceException::class.java)
            .verify()
    }

    @Test
    fun `should throw not found if null`() {
        assertThrownBy<NotFoundResourceException> {
            null.throwNotFoundIfNull("null value")
        }
    }

    @Test
    fun `should if not null`() {
        "".throwNotFoundIfNull()
    }
}
