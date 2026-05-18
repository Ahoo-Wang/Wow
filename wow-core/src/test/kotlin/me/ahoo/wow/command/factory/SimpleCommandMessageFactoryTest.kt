package me.ahoo.wow.command.factory

import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.command.validation.NoOpValidator
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SimpleCommandMessageFactoryTest {

    @Test
    fun `should create if not found`() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        val factory = SimpleCommandMessageFactory(NoOpValidator, registry)
        val command = MockCreateCommand("")
        factory.create<MockCreateCommand>(command)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `should create if found`() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        registry.register(MockCommandBuilderRewriter())
        val factory = SimpleCommandMessageFactory(NoOpValidator, registry)
        val command = MockCreateCommand("")
        factory.create<MockCreateCommand>(command)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun `should create if empty`() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        registry.register(EmptyCommandBuilderRewriter())
        val factory = SimpleCommandMessageFactory(NoOpValidator, registry)
        val command = MockCreateCommand("")
        factory.create<MockCreateCommand>(command)
            .test()
            .expectError(RewriteNoCommandException::class.java)
            .verify()
    }
}
