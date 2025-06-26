package me.ahoo.wow.command.factory

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.MockCreateCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class SimpleCommandBuilderRewriterRegistryTest {

    @Test
    fun register() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        registry.register(MockCommandBuilderRewriter())
        registry.getRewriter(MockCreateCommand::class.java).assert().isNotNull()
        registry.unregister(MockCreateCommand::class.java)
        registry.getRewriter(MockCreateCommand::class.java).assert().isNull()
    }

    @Test
    fun registerBlocked() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        registry.register(MockBlockingCommandBuilderRewriter())
        registry.getRewriter(
            MockCreateCommand::class.java
        ).assert().isInstanceOf(BlockingCommandBuilderRewriter::class.java)
        (registry.getRewriter(
            MockCreateCommand::class.java
        ) as BlockingCommandBuilderRewriter).delegate.assert().isInstanceOf(
            MockBlockingCommandBuilderRewriter::class.java
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
