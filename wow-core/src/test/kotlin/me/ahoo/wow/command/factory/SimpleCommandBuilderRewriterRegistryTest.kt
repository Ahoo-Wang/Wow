package me.ahoo.wow.command.factory

import me.ahoo.wow.command.MockCreateCommand
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class SimpleCommandBuilderRewriterRegistryTest {

    @Test
    fun register() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        registry.register(MockCommandBuilderRewriter())
        assertThat(
            registry.getRewriter(MockCreateCommand::class.java),
            notNullValue()
        )
        registry.unregister(MockCreateCommand::class.java)
        assertThat(
            registry.getRewriter(MockCreateCommand::class.java),
            nullValue()
        )
    }

    @Test
    fun registerBlocked() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        registry.register(MockBlockingCommandBuilderRewriter())
        assertThat(
            registry.getRewriter(MockCreateCommand::class.java),
            instanceOf(BlockingCommandBuilderRewriter::class.java)
        )
        assertThat(
            (registry.getRewriter(MockCreateCommand::class.java) as BlockingCommandBuilderRewriter).delegate,
            instanceOf(MockBlockingCommandBuilderRewriter::class.java)
        )
    }
}

class MockCommandBuilderRewriter : CommandBuilderRewriter {
    override val supportedCommandType: Class<MockCreateCommand>
        get() = MockCreateCommand::class.java

    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> {
        return Mono.just(commandBuilder)
    }
}

class EmptyCommandBuilderRewriter : CommandBuilderRewriter {
    override val supportedCommandType: Class<MockCreateCommand>
        get() = MockCreateCommand::class.java

    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> {
        return Mono.empty()
    }
}
