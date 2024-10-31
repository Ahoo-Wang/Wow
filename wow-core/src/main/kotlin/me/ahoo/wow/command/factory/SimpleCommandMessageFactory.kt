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

import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.command.toCommandMessage
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class SimpleCommandMessageFactory(
    private val commandBuilderRewriterRegistry: CommandBuilderRewriterRegistry
) : CommandMessageFactory {

    override fun <TARGET : Any> create(commandBuilder: CommandBuilder): Mono<CommandMessage<TARGET>> {
        val body = commandBuilder.body
        val rewriter = commandBuilderRewriterRegistry.getRewriter(body.javaClass)
            ?: return commandBuilder.toCommandMessage<TARGET>().toMono()
        return rewriter.rewrite(commandBuilder)
            .checkpoint("Rewrite $rewriter [SimpleCommandMessageFactory]")
            .map {
                it.toCommandMessage()
            }
    }
}
