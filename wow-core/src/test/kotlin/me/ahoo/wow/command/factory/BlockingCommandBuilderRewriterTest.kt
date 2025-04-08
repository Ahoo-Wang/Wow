package me.ahoo.wow.command.factory

import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.command.MockCreateCommand
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test

class BlockingCommandBuilderRewriterTest {

    @Test
    fun rewrite() {
        val commandBuilder = MutableCommandBuilder(Any())
        val commandBuilderRewriter = BlockingCommandBuilderRewriter(
            MockBlockingCommandBuilderRewriter()
        )
        commandBuilderRewriter.rewrite(commandBuilder)
            .test()
            .verifyComplete()
    }
}

class MockBlockingCommandBuilderRewriter : CommandBuilderRewriter {
    override val supportedCommandType: Class<MockCreateCommand>
        get() = MockCreateCommand::class.java

    @Blocking
    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> {
        check(!Schedulers.isInNonBlockingThread())
        return Mono.empty()
    }
}
