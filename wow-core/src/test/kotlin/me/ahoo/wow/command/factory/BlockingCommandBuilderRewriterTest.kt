/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.command.factory

import me.ahoo.test.asserts.assert
import me.ahoo.wow.command.factory.CommandBuilder.Companion.commandBuilder
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.concurrent.atomic.AtomicReference

class BlockingCommandBuilderRewriterTest {

    @Test
    fun `should execute delegate rewrite on configured scheduler`() {
        val scheduler = Schedulers.newSingle("command-rewriter-test")
        val threadName = AtomicReference<String>()
        val builder = BlockingFactoryCommand("body").commandBuilder()
        val delegate = object : CommandBuilderRewriter {
            override val supportedCommandType: Class<*> = BlockingFactoryCommand::class.java

            override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> =
                Mono.fromCallable {
                    threadName.set(Thread.currentThread().name)
                    commandBuilder.requestId("rewritten")
                }
        }

        try {
            val rewriter = BlockingCommandBuilderRewriter(delegate, scheduler)

            StepVerifier.create(rewriter.rewrite(builder))
                .expectNext(builder)
                .verifyComplete()

            builder.requestId.assert().isEqualTo("rewritten")
            threadName.get().assert().startsWith("command-rewriter-test")
        } finally {
            scheduler.dispose()
        }
    }
}

private data class BlockingFactoryCommand(val value: String)
