package me.ahoo.wow.filter

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class AbstractHandlerTest {

    @Test
    fun handle() {
        val chain: FilterChain<String> = FilterChain {
            IllegalStateException().toMono()
        }
        val handler = object : AbstractHandler<String>(chain, LogResumeErrorHandler()) {
        }

        handler.handle("test").test().verifyComplete()
    }

    @Test
    fun handleIfErrorAccessor() {
        val context = mockk<ErrorAccessor<*>> {
            every { setError(any()) } returns this
        }
        val chain: FilterChain<ErrorAccessor<*>> = FilterChain {
            IllegalStateException().toMono()
        }
        val handler = object : AbstractHandler<ErrorAccessor<*>>(chain, LogResumeErrorHandler()) {
        }

        handler.handle(context).test().verifyComplete()

        verify {
            context.setError(any())
        }
    }
}
