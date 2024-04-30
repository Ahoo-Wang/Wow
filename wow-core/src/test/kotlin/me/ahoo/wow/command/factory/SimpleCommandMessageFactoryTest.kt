package me.ahoo.wow.command.factory

import me.ahoo.wow.command.MockCreateCommand
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SimpleCommandMessageFactoryTest {

    @Test
    fun createIfNotFound() {
        val registry = SimpleCommandOptionsExtractorRegistry()
        val factory = SimpleCommandMessageFactory(registry)
        val command = MockCreateCommand("")
        factory.create(command)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun createIfFound() {
        val registry = SimpleCommandOptionsExtractorRegistry()
        registry.register(MockCommandOptionsExtractor())
        val factory = SimpleCommandMessageFactory(registry)
        val command = MockCreateCommand("")
        factory.create(command)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }
}