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

import io.github.oshai.kotlinlogging.KotlinLogging
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap

/**
 * CommandBuilderRewriter
 *
 */
interface CommandBuilderRewriter {
    val supportedCommandType: Class<*>
    fun rewrite(commandBuilder: CommandBuilder): Mono<CommandBuilder>
}

interface CommandBuilderRewriterRegistry {
    fun register(extractor: CommandBuilderRewriter)
    fun unregister(commandType: Class<*>)
    fun getRewriter(commandType: Class<*>): CommandBuilderRewriter?
}

class SimpleCommandBuilderRewriterRegistry : CommandBuilderRewriterRegistry {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private val registrar = ConcurrentHashMap<Class<*>, CommandBuilderRewriter>()
    override fun register(extractor: CommandBuilderRewriter) {
        val previous = registrar.put(extractor.supportedCommandType, extractor)
        log.info {
            "Register - supportedCommandType:[${extractor.supportedCommandType}] - previous:[$previous],current:[$extractor]."
        }
    }

    override fun unregister(commandType: Class<*>) {
        val removed = registrar.remove(commandType)
        log.info {
            "Unregister - commandType:[$commandType] - removed:[$removed]."
        }
    }

    override fun getRewriter(commandType: Class<*>): CommandBuilderRewriter? {
        return registrar[commandType]
    }
}
