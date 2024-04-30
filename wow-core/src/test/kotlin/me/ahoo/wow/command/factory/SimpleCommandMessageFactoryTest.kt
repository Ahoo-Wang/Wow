package me.ahoo.wow.command.factory

import io.mockk.every
import io.mockk.mockk
import jakarta.validation.Path
import jakarta.validation.Validator
import me.ahoo.wow.command.MockCreateCommand
import me.ahoo.wow.command.validation.NoOpValidator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

class SimpleCommandMessageFactoryTest {

    @Test
    fun createIfNotFound() {
        val registry = SimpleCommandOptionsExtractorRegistry()
        val factory = SimpleCommandMessageFactory(NoOpValidator, registry)
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
        val factory = SimpleCommandMessageFactory(NoOpValidator, registry)
        val command = MockCreateCommand("")
        factory.create(command)
            .test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun validateError() {
        val path = mockk<Path>()
        every { path.toString() } returns "propertyPath"
        val constraintViolations = setOf(
            mockk<jakarta.validation.ConstraintViolation<Any>> {
                every { propertyPath } returns path
                every { message } returns "message"
            }
        )
        val validator = mockk<Validator> {
            every { validate<Any>(any()) } returns constraintViolations
        }
        val registry = SimpleCommandOptionsExtractorRegistry()
        val factory = SimpleCommandMessageFactory(validator, registry)
        val commandMessage = factory.create(MockCreateCommand(""))
        Assertions.assertThrows(CommandValidationException::class.java) {
            commandMessage.block()
        }
    }
}
