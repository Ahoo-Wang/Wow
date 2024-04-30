package me.ahoo.wow.command.factory

import me.ahoo.wow.command.MockCreateCommand
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class DefaultCommandOptionsExtractorRegistryTest {

    @Test
    fun register() {
        val registry = DefaultCommandOptionsExtractorRegistry()
        registry.register(MockCommandOptionsExtractor())
        assertThat(registry.getExtractor(MockCreateCommand::class.java), notNullValue())
        registry.unregister(MockCreateCommand::class.java)
        assertThat(registry.getExtractor(MockCreateCommand::class.java), nullValue())
    }
}

class MockCommandOptionsExtractor : CommandOptionsExtractor<MockCreateCommand> {
    override val supportedCommandType: Class<MockCreateCommand>
        get() = MockCreateCommand::class.java

    override fun extract(command: MockCreateCommand, options: CommandOptions): Mono<CommandOptions> {
        return Mono.just(options)
    }
}
