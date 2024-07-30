package me.ahoo.wow.query.filter

import me.ahoo.wow.query.filter.Contexts.getRawRequest
import me.ahoo.wow.query.filter.Contexts.writeRawRequest
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.test.test

class ContextsTest {

    @Test
    fun writeRawRequest() {
        Mono.deferContextual {
            assertThat(it.getRawRequest<ContextsTest>(), equalTo(this))
            Mono.empty<Void>()
        }.writeRawRequest(this)
            .test()
            .verifyComplete()
    }
}
