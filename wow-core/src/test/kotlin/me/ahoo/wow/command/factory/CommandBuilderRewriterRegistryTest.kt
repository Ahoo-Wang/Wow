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
import me.ahoo.wow.api.annotation.Blocking
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class CommandBuilderRewriterRegistryTest {

    @Test
    fun `should register replace lookup and unregister rewriter`() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        val first = NonBlockingRegistryRewriter("first")
        val second = NonBlockingRegistryRewriter("second")

        registry.getRewriter(RegistryCommand::class.java).assert().isNull()

        registry.register(first)
        registry.getRewriter(RegistryCommand::class.java).assert().isSameAs(first)

        registry.register(second)
        registry.getRewriter(RegistryCommand::class.java).assert().isSameAs(second)

        registry.unregister(RegistryCommand::class.java)
        registry.getRewriter(RegistryCommand::class.java).assert().isNull()
    }

    @Test
    fun `should wrap blocking rewriter on registration`() {
        val registry = SimpleCommandBuilderRewriterRegistry()
        val blocking = BlockingRegistryRewriter()

        registry.register(blocking)

        val registered = registry.getRewriter(RegistryCommand::class.java)
        registered.assert().isInstanceOf(BlockingCommandBuilderRewriter::class.java)
        (registered as BlockingCommandBuilderRewriter).delegate.assert().isSameAs(blocking)
    }
}

private data class RegistryCommand(val value: String)

private class NonBlockingRegistryRewriter(private val name: String) : CommandBuilderRewriter {
    override val supportedCommandType: Class<*> = RegistryCommand::class.java

    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> =
        Mono.just(commandBuilder.requestId(name))
}

private class BlockingRegistryRewriter : CommandBuilderRewriter {
    override val supportedCommandType: Class<*> = RegistryCommand::class.java

    @Blocking
    override fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder> =
        Mono.just(commandBuilder)
}
